//! Teclado nativo do Android (IME) para os campos de texto do jogo.
//!
//! O Ruffle avisa a camada de interface (`UiBackend`) quando um campo
//! editável ganha ou perde o foco; aqui isso abre ou fecha o teclado do
//! sistema. O que se digita chega como estado de texto do IME (texto inteiro
//! mais região de composição, usada pelas sugestões do teclado); a diferença
//! para o estado anterior vira backspaces e caracteres para o jogo.

use android_activity::AndroidApp;
use android_activity::input::{TextInputState, TextSpan};
use ruffle_core::backend::ui::{
    DialogResultFuture, FileFilter, FontDefinition, FullscreenError, LanguageIdentifier,
    MouseCursor, MultiDialogResultFuture, NullUiBackend, UiBackend,
};
use ruffle_core::events::{KeyDescriptor, KeyLocation, LogicalKey, NamedKey, PhysicalKey};
use ruffle_core::PlayerEvent;
use ruffle_core::font::FontQuery;
use url::Url;

/// `UiBackend` padrão, exceto pelo teclado virtual, que vira o teclado do sistema.
pub struct AndroidUiBackend {
    app: AndroidApp,
    inner: NullUiBackend,
}

impl AndroidUiBackend {
    pub fn new(app: AndroidApp) -> Self {
        Self {
            app,
            inner: NullUiBackend::new(),
        }
    }
}

impl UiBackend for AndroidUiBackend {
    fn mouse_visible(&self) -> bool {
        self.inner.mouse_visible()
    }

    fn set_mouse_visible(&mut self, visible: bool) {
        self.inner.set_mouse_visible(visible)
    }

    fn set_mouse_cursor(&mut self, cursor: MouseCursor) {
        self.inner.set_mouse_cursor(cursor)
    }

    fn clipboard_content(&mut self) -> String {
        self.inner.clipboard_content()
    }

    fn set_clipboard_content(&mut self, content: String) {
        self.inner.set_clipboard_content(content)
    }

    fn set_fullscreen(&mut self, is_full: bool) -> Result<(), FullscreenError> {
        self.inner.set_fullscreen(is_full)
    }

    fn display_root_movie_download_failed_message(&self, invalid_swf: bool, fetched_error: String) {
        self.inner
            .display_root_movie_download_failed_message(invalid_swf, fetched_error)
    }

    fn message(&self, message: &str) {
        self.inner.message(message)
    }

    fn open_virtual_keyboard(&self) {
        // Vazio: o teclado começa sem texto; o diff parte daqui.
        self.app.set_text_input_state(empty_state());
        // Pedido explícito: com SHOW_IMPLICIT o Android pode ignorar o pedido.
        self.app.show_soft_input(false);
    }

    fn close_virtual_keyboard(&self) {
        self.app.hide_soft_input(false);
    }

    fn language(&self) -> LanguageIdentifier {
        self.inner.language()
    }

    fn display_unsupported_video(&self, url: Url) {
        self.inner.display_unsupported_video(url)
    }

    fn load_device_font(&self, query: &FontQuery, register: &mut dyn FnMut(FontDefinition)) {
        self.inner.load_device_font(query, register)
    }

    fn sort_device_fonts(
        &self,
        query: &FontQuery,
        register: &mut dyn FnMut(FontDefinition),
    ) -> Vec<FontQuery> {
        self.inner.sort_device_fonts(query, register)
    }

    fn display_file_open_dialog(&mut self, filters: Vec<FileFilter>) -> Option<DialogResultFuture> {
        self.inner.display_file_open_dialog(filters)
    }

    fn display_file_open_dialog_multiple(
        &mut self,
        filters: Vec<FileFilter>,
    ) -> Option<MultiDialogResultFuture> {
        self.inner.display_file_open_dialog_multiple(filters)
    }

    fn display_file_save_dialog(
        &mut self,
        file_name: String,
        title: String,
    ) -> Option<DialogResultFuture> {
        self.inner.display_file_save_dialog(file_name, title)
    }

    fn close_file_dialog(&mut self) {
        self.inner.close_file_dialog()
    }
}

fn empty_state() -> TextInputState {
    TextInputState {
        text: String::new(),
        selection: TextSpan { start: 0, end: 0 },
        compose_region: None,
    }
}

fn named_key(key: NamedKey) -> KeyDescriptor {
    KeyDescriptor {
        physical_key: PhysicalKey::Unknown,
        logical_key: LogicalKey::Named(key),
        key_location: KeyLocation::Standard,
    }
}

/// Converte as mudanças de texto do IME em eventos de teclado do jogo.
#[derive(Default)]
pub struct ImeText {
    /// Texto do IME já repassado ao jogo.
    sent: String,
}

impl ImeText {
    /// Eventos para ir do texto já enviado ao novo estado do IME.
    pub fn update(&mut self, app: &AndroidApp, state: TextInputState) -> Vec<PlayerEvent> {
        let (removed, inserted) = diff(&self.sent, &state.text);
        let mut events = Vec::new();
        for _ in 0..removed {
            let key = named_key(NamedKey::Backspace);
            events.push(PlayerEvent::KeyDown { key });
            events.push(PlayerEvent::KeyUp { key });
        }
        events.extend(inserted.chars().map(|codepoint| PlayerEvent::TextInput { codepoint }));

        if state.compose_region.is_none() && !state.text.is_empty() {
            // Nada em composição: o texto já está no jogo, então o IME pode
            // recomeçar vazio (evita reenviar tudo a cada tecla).
            self.sent.clear();
            app.set_text_input_state(empty_state());
        } else {
            self.sent = state.text;
        }
        events
    }

    /// Tecla de ação do teclado (Enter/Enviar).
    pub fn action(&mut self) -> Vec<PlayerEvent> {
        self.sent.clear();
        let key = named_key(NamedKey::Enter);
        vec![PlayerEvent::KeyDown { key }, PlayerEvent::KeyUp { key }]
    }
}

/// Quantos caracteres apagar do fim de `old` e o que acrescentar para virar `new`.
fn diff<'a>(old: &str, new: &'a str) -> (usize, &'a str) {
    let common: usize = old
        .chars()
        .zip(new.chars())
        .take_while(|(a, b)| a == b)
        .map(|(a, _)| a.len_utf8())
        .sum();
    (old[common..].chars().count(), &new[common..])
}

#[cfg(test)]
mod tests {
    use super::diff;

    #[test]
    fn diffs_from_common_prefix() {
        assert_eq!(diff("", "olá"), (0, "olá"));
        assert_eq!(diff("ola", "olá"), (1, "á"));
        assert_eq!(diff("casa", "ca"), (2, ""));
        assert_eq!(diff("abc", "abc"), (0, ""));
    }
}
