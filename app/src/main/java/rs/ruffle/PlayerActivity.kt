package rs.ruffle

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import java.io.DataInputStream
import java.io.File
import java.io.IOException

class PlayerActivity : GameActivity() {
    @Suppress("unused")
    // Used by Rust
    private val swfBytes: ByteArray?
        get() {
            val uri = intent.data
            if (uri?.scheme == "content") {
                try {
                    contentResolver.openInputStream(uri).use { inputStream ->
                        if (inputStream == null) {
                            return null
                        }
                        val bytes = ByteArray(inputStream.available())
                        val dataInputStream = DataInputStream(inputStream)
                        dataInputStream.readFully(bytes)
                        return bytes
                    }
                } catch (ignored: IOException) {
                }
            }
            return null
        }

    @Suppress("unused")
    // Used by Rust
    private val swfUri: String?
        get() {
            return intent.dataString
        }

    @Suppress("unused")
    // Used by Rust: FlashVars no formato de query string
    private val swfParameters: String?
        get() {
            return intent.getStringExtra(EXTRA_FLASHVARS)
        }

    @Suppress("unused")
    // Used by Rust
    private val traceOutput: String?
        get() {
            return intent.getStringExtra("traceOutput")
        }

    @Suppress("unused")
    // Used by Rust
    private fun navigateToUrl(url: String?) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private var loc = IntArray(2)

    @Suppress("unused")
    // Handle of an EventLoopProxy over in rust-land
    private val eventLoopHandle: Long = 0

    @Suppress("unused")
    // Used by Rust
    private val locInWindow: IntArray
        get() {
            mSurfaceView.getLocationInWindow(loc)
            return loc
        }

    @Suppress("unused")
    // Used by Rust
    private val surfaceWidth: Int
        get() = mSurfaceView.width

    @Suppress("unused")
    // Used by Rust
    private val surfaceHeight: Int
        get() = mSurfaceView.height

    private external fun runContextMenuCallback(index: Int)
    private external fun clearContextMenu()

    @Suppress("unused")
    // Used by Rust
    private fun showContextMenu(items: Array<String>) {
        runOnUiThread {
            val popup = PopupMenu(this, mSurfaceView)
            val menu = popup.menu
            if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
                menu.setGroupDividerEnabled(true)
            }
            var group = 1
            for (i in items.indices) {
                val elements = items[i].split(" ".toRegex(), limit = 4).toTypedArray()
                val enabled = elements[0].toBoolean()
                val separatorBefore = elements[1].toBoolean()
                val checked = elements[2].toBoolean()
                val caption = elements[3]
                if (separatorBefore) group += 1
                val item = menu.add(group, i, Menu.NONE, caption)
                item.setEnabled(enabled)
                if (checked) {
                    item.setCheckable(true)
                    item.setChecked(true)
                }
            }
            val exitItemId: Int = items.size
            menu.add(group, exitItemId, Menu.NONE, "Exit")
            popup.setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == exitItemId) {
                    finish()
                } else {
                    runContextMenuCallback(item.itemId)
                }
                true
            }
            popup.setOnDismissListener { clearContextMenu() }
            popup.show()
        }
    }

    @Suppress("unused")
    // Used by Rust
    private fun getAndroidDataStorageDir(): String {
        // TODO It can also be placed in an external storage path in the future to share archived content
        val storageDirPath = "${filesDir.absolutePath}/ruffle/shared_objects"
        val storageDir = File(storageDirPath)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return storageDirPath
    }

    @Suppress("unused")
    // Used by Rust
    private fun getAndroidCacheDir(): String {
        // O cache HTTP ficava em filesDir, que entra no backup automático.
        val oldCacheDir = File(filesDir, "ruffle/http_cache")
        if (oldCacheDir.exists()) {
            oldCacheDir.deleteRecursively()
        }
        return "${cacheDir.absolutePath}/http_cache"
    }

    // O jogo ocupa a tela inteira; textos usam o teclado nativo do Android
    // (aberto pelo Ruffle quando um campo editável ganha foco, ver src/ime.rs).
    override fun onCreateSurfaceView() {
        mSurfaceView = InputEnabledSurfaceView(this)
        mSurfaceView.contentDescription = "Ruffle Player"
        contentViewId = View.generateViewId()
        mSurfaceView.id = contentViewId
        setContentView(mSurfaceView)
        // Sem foco em modo de toque, o teclado do sistema não se conecta à view.
        mSurfaceView.isFocusable = true
        mSurfaceView.isFocusableInTouchMode = true
        mSurfaceView.requestFocus()
        mSurfaceView.holder.addCallback(this)
        ViewCompat.setOnApplyWindowInsetsListener(mSurfaceView, this)
    }

    // Com o teclado do sistema aberto, o jogo sobe pela altura dele: o chat e os
    // demais campos na parte de baixo ficam visíveis acima do teclado. O Android
    // ajusta as coordenadas dos toques pelo deslocamento.
    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        mSurfaceView.translationY = -keyboard.toFloat()
        return super.onApplyWindowInsets(v, insets)
    }

    private fun hideSystemUI() {
        // This will put the game behind any cutouts and waterfalls on devices which have
        // them, so the corresponding insets will be non-zero.
        if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // From API 30 onwards, this is the recommended way to hide the system UI, rather than
        // using View.setSystemUiVisibility.
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(
            window,
            decorView
        )
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.hide(WindowInsetsCompat.Type.displayCutout())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        nativeInit { message ->
            Log.e("ruffle", "Handling panic: $message")
            startActivity(
                Intent(this, PanicActivity::class.java).apply {
                    putExtra("message", message)
                }
            )
        }
        // When true, the app will fit inside any system UI windows.
        // When false, we render behind any system UI windows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()
        // You can set IME fields here or in native code using GameActivity_setImeEditorInfoFields.
        // We set the fields in native_engine.cpp.
        // super.setImeEditorInfoFields(InputType.TYPE_CLASS_TEXT,
        //     IME_ACTION_NONE, IME_FLAG_NO_FULLSCREEN );
        requestNoStatusBarFeature()
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
    }

    // Used by Rust
    @Suppress("unused")
    val isGooglePlayGames: Boolean
        get() {
            val pm = packageManager
            return pm.hasSystemFeature("com.google.android.play.feature.HPE_EXPERIENCE")
        }

    private fun requestNoStatusBarFeature() {
        // Hiding the status bar this way makes it see through when pulled down
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowInsetsControllerCompat(
            window,
            mSurfaceView
        ).hide(WindowInsetsCompat.Type.statusBars())
    }

    companion object {
        const val EXTRA_FLASHVARS = "com.eldialabs.legendonline.FLASHVARS"
        const val EXTRA_TITLE = "com.eldialabs.legendonline.TITLE"

        init {
            // load the native activity
            System.loadLibrary("ruffle_android")
        }

        @JvmStatic
        private external fun nativeInit(crashCallback: CrashCallback)

    }

    fun interface CrashCallback {
        fun onCrash(message: String)
    }
}
