package com.krishna.apkguard

import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.Locale
import java.util.zip.ZipFile
import android.text.Editable
import android.text.TextWatcher

private object WallUi {
    val background = Color.BLACK
    val surface = Color.rgb(20, 20, 20)
    val surfaceRaised = Color.rgb(34, 34, 34)
    val surfaceSoft = Color.rgb(48, 48, 48)
    val ink = Color.rgb(246, 244, 250)
    val muted = Color.rgb(203, 199, 212)
    val subtle = Color.rgb(161, 157, 172)
    val line = Color.rgb(119, 115, 130)
    val teal = Color.rgb(110, 224, 205)
    val tealDark = Color.rgb(41, 150, 190)
    val blue = Color.rgb(66, 163, 229)
    val violet = Color.rgb(169, 150, 217)
    val coral = Color.rgb(255, 132, 111)
    val green = Color.rgb(85, 216, 154)
    val amber = Color.rgb(255, 193, 96)
    val red = Color.rgb(255, 112, 122)
}

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var apkUri: Uri? = null
    private var extractorUri: Uri? = null
    private var selectedInstalledApp: ApplicationInfo? = null
    private var pendingExport = false
    private var installedEntries: List<InstalledAppEntry> = emptyList()
    private lateinit var installedList: LinearLayout
    private lateinit var installedSearch: EditText
    private var installedScreenToken = 0
    private lateinit var extractorFileLabel: TextView
    private lateinit var extractorButton: Button
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private data class InstalledAppEntry(val app: ApplicationInfo, val label: String, val version: String, val size: String, val icon: Drawable)
    private lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationView
    private lateinit var homeScroll: ScrollView
    private lateinit var selectedFile: TextView
    private lateinit var stepRows: List<Pair<TextView, TextView>>
    private lateinit var progress: ProgressBar
    private lateinit var percent: TextView
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var disableDebugSwitch: SwitchMaterial
    private lateinit var disableBackupSwitch: SwitchMaterial
    private lateinit var requireSignatureSwitch: SwitchMaterial
    private lateinit var encryptStringsSwitch: SwitchMaterial
    private lateinit var encryptAssetsSwitch: SwitchMaterial
    private lateinit var obfuscateResourcesSwitch: SwitchMaterial
    private lateinit var blockRootSwitch: SwitchMaterial
    private lateinit var blockEmulatorSwitch: SwitchMaterial
    private lateinit var blockDebuggerSwitch: SwitchMaterial
    private lateinit var blockInstrumentationSwitch: SwitchMaterial

    private val watcher = object : Runnable {
        override fun run() {
            if (!::detail.isInitialized) return
            val prefs = getSharedPreferences(ApkProtectionService.PREFS, MODE_PRIVATE)
            val message = prefs.getString("message", "") ?: ""
            val value = prefs.getInt("progress", 0).coerceIn(0, 100)
            val active = prefs.getBoolean("active", false)
            if (message.isNotBlank()) {
                detail.text = message
                progress.progress = value
                percent.text = "$value%"
                startButton.isEnabled = !active
                startButton.alpha = if (active) .55f else 1f
                colorProgress(message)
                updateSteps(message, value)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        showSplash()
    }

    private fun showSplash() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(WallUi.background)
            setPadding(dp(34), dp(30), dp(34), dp(30))
        }
        val mark = TextView(this).apply {
            text = "◈"
            textSize = 58f
            gravity = Gravity.CENTER
            setTextColor(WallUi.teal)
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(mark, LinearLayout.LayoutParams(-1, dp(80)))
        root.addView(text("APK WALL", 30, WallUi.ink, true).apply { gravity = Gravity.CENTER })
        root.addView(text("LOCAL APK PROTECTION", 12, WallUi.teal, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .2f
            setPadding(0, dp(8), 0, dp(28))
        })
        root.addView(text("Crafted by Krishna", 14, WallUi.muted, false).apply { gravity = Gravity.CENTER })
        setContentView(root)
        insets(root)
        val markView = root.getChildAt(0)
        val titleView = root.getChildAt(1)
        val subtitleView = root.getChildAt(2)
        val creditView = root.getChildAt(3)
        listOf(markView, titleView, subtitleView, creditView).forEach { it.alpha = 0f; it.translationY = dp(14).toFloat() }
        markView.scaleX = .72f; markView.scaleY = .72f
        markView.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(620).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        titleView.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(500).start()
        subtitleView.animate().alpha(1f).translationY(0f).setStartDelay(300).setDuration(500).start()
        creditView.animate().alpha(1f).translationY(0f).setStartDelay(420).setDuration(500).start()
        handler.postDelayed({ if (!isFinishing) buildHome() }, 1150)
    }

    /** Extensible landing page: add a FeatureSpec to the list below for another tool tile. */
    private fun buildHome() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WallUi.background)
        }
        page.addView(hubHeader(), LinearLayout.LayoutParams(-1, dp(82)))
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(165, 184, 198), Color.rgb(61, 126, 221))
            ).apply { cornerRadius = dp(26).toFloat() }
        }
        hero.addView(text("◈", 54, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        hero.addView(text("Welcome to APK Wall", 24, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        hero.addView(text("A toolkit for safer APK releases", 13, Color.rgb(236, 241, 255), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        body.addView(hero, LinearLayout.LayoutParams(-1, dp(210)).apply { bottomMargin = dp(26) })

        body.addView(text("Your toolkit", 20, WallUi.ink, true).apply { setPadding(dp(2), 0, 0, dp(3)) })
        body.addView(text("Add future tools as cards without changing the dashboard layout.", 12, WallUi.muted, false).apply { setPadding(dp(2), 0, 0, dp(12)) })

        val features = listOf(
            FeatureSpec("Protect APK", "Wrap, harden & sign", "🔒", WallUi.blue) { buildProtector() },
            FeatureSpec("APK Extractor", "Unpack APK contents", "▣", WallUi.coral) { showExtractor() },
            FeatureSpec("Hook Generator", "Generate C++ templates", "⌘", WallUi.amber) { showHookGenerator() },
            FeatureSpec("Protection methods", "See what is supported", "🛡", WallUi.violet) { showMethods() },
            FeatureSpec("About APK Wall", "Compatibility & limits", "ⓘ", WallUi.teal) { showAbout() },
            FeatureSpec("Credits", "Meet the creator", "✦", WallUi.amber) { openCredits() }
        )
        var row: LinearLayout? = null
        features.forEachIndexed { index, feature ->
            if (index % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                body.addView(row, LinearLayout.LayoutParams(-1, dp(142)).apply { bottomMargin = dp(20) })
            }
            row?.addView(featureCard(feature), LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (index % 2 == 0) rightMargin = dp(8) else leftMargin = dp(8)
            })
        }
        body.addView(text("More tools can be added here later — the core processing engine stays separate from this presentation layer.", 12, WallUi.subtle, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        insets(page)
        page.alpha = 0f; page.translationY = dp(18).toFloat()
        page.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        for (index in 0 until body.childCount) {
            val child = body.getChildAt(index)
            child.alpha = 0f; child.translationY = dp(10).toFloat()
            child.animate().alpha(1f).translationY(0f).setStartDelay((index * 45L).coerceAtMost(300L)).setDuration(360).start()
        }
    }

    private data class FeatureSpec(val title: String, val subtitle: String, val icon: String, val accent: Int, val action: () -> Unit)

    private fun featureCard(feature: FeatureSpec): View {
        val card = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(WallUi.surfaceRaised)
            strokeColor = WallUi.line
            strokeWidth = dp(1)
            isClickable = true
            isFocusable = true
            setOnClickListener { feature.action() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(7))
        }
        val icon = TextView(this).apply {
            text = feature.icon
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(feature.accent)
            background = rounded(Color.argb(45, Color.red(feature.accent), Color.green(feature.accent), Color.blue(feature.accent)), 18)
        }
        content.addView(icon, LinearLayout.LayoutParams(dp(50), dp(50)).apply { bottomMargin = dp(6) })
        content.addView(text(feature.title, 13, WallUi.ink, true).apply { gravity = Gravity.CENTER })
        content.addView(text(feature.subtitle, 10, WallUi.muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
        card.addView(content)
        return card
    }

    private fun hubHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(8), dp(12), dp(7))
        setBackgroundColor(Color.rgb(66, 163, 229))
        val titles = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        titles.addView(text("APK Wall", 25, Color.WHITE, false))
        titles.addView(text("A toolkit for APK protection", 14, Color.WHITE, true).apply { setPadding(0, dp(3), 0, 0) })
        addView(titles, LinearLayout.LayoutParams(0, -1, 1f))
        addView(text("◉", 26, Color.WHITE, false).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0); contentDescription = "Tools" })
        addView(text("⋮", 30, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(4), 0); contentDescription = "More options" })
    }

    private fun showExtractor() {
        val token = ++installedScreenToken
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        page.addView(topBar("Installed Apps", false) { buildHome() }, LinearLayout.LayoutParams(-1, dp(68)))
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(12)) }
        header.addView(text("Export any installed app", 23, WallUi.ink, true))
        header.addView(text("Tap an app to inspect it and extract its base APK.", 12, WallUi.muted, false).apply { setPadding(0, dp(4), 0, dp(12)) })
        installedSearch = EditText(this).apply {
            hint = "Search app name or package"
            textSize = 15f; setSingleLine(true); setTextColor(WallUi.ink); setHintTextColor(WallUi.subtle)
            setPadding(dp(16), 0, dp(16), 0); background = rounded(WallUi.surfaceSoft, 14)
        }
        header.addView(installedSearch, LinearLayout.LayoutParams(-1, dp(50)))
        page.addView(header)
        val scroll = ScrollView(this).apply { clipToPadding = false }
        installedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        scroll.addView(installedList)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page); insets(page)
        installedSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderInstalledList(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderInstalledLoading()
        ioExecutor.execute {
            val loaded = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.sourceDir?.isNotBlank() == true }
                .mapNotNull { app ->
                    try {
                        val info = packageManager.getPackageInfo(app.packageName, 0)
                        InstalledAppEntry(app, app.loadLabel(packageManager).toString(), info.versionName ?: "unknown", formatBytes(File(app.sourceDir).length()), app.loadIcon(packageManager))
                    } catch (_: Exception) { null }
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
            runOnUiThread {
                if (token == installedScreenToken && !isFinishing) { installedEntries = loaded; renderInstalledList(installedSearch.text.toString()) }
            }
        }
    }

    private fun renderInstalledLoading() {
        installedList.removeAllViews()
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        installedList.addView(spinner, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(28); bottomMargin = dp(14) })
        installedList.addView(text("Loading all installed apps…", 14, WallUi.muted, false).apply { gravity = Gravity.CENTER })
    }

    private fun renderInstalledList(query: String) {
        if (!::installedList.isInitialized || installedEntries.isEmpty()) return
        val q = query.trim().lowercase(Locale.ROOT)
        val matches = installedEntries.filter { q.isBlank() || it.label.lowercase(Locale.ROOT).contains(q) || it.app.packageName.lowercase(Locale.ROOT).contains(q) }
        installedList.removeAllViews()
        if (matches.isEmpty()) {
            installedList.addView(text("No installed apps match your search", 14, WallUi.muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
            return
        }
        matches.forEach { entry ->
            val row = MaterialCardView(this).apply {
                radius = dp(9).toFloat(); cardElevation = dp(1).toFloat(); setCardBackgroundColor(WallUi.surfaceRaised)
                strokeColor = Color.rgb(62, 62, 62); strokeWidth = 1; isClickable = true; isFocusable = true
                setOnClickListener { showAppDetails(entry.app) }
            }
            val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
            val icon = ImageView(this).apply { setImageDrawable(entry.icon); scaleType = ImageView.ScaleType.CENTER_CROP }
            content.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(14) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(entry.label, 16, WallUi.ink, true))
            labels.addView(text("${entry.version}   ${entry.size}", 12, WallUi.muted, false).apply { setPadding(0, dp(4), 0, 0) })
            labels.addView(text(entry.app.packageName, 12, WallUi.subtle, false).apply { setPadding(0, dp(4), 0, 0) })
            content.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(content)
            installedList.addView(row, LinearLayout.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(12) })
        }
    }

    private fun showAppDetails(app: ApplicationInfo) {
        val info = packageManager.getPackageInfo(app.packageName, 0)
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)); background = rounded(WallUi.surfaceRaised, 22) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply { setImageDrawable(app.loadIcon(packageManager)); scaleType = ImageView.ScaleType.CENTER_CROP }
        heading.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(14) })
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text(app.loadLabel(packageManager).toString(), 20, WallUi.ink, true))
        titleBox.addView(text("Installed application", 12, WallUi.teal, false).apply { setPadding(0, dp(5), 0, 0) })
        heading.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(heading)
        root.addView(text("APK INFORMATION", 11, WallUi.teal, true).apply { letterSpacing = .12f; setPadding(0, dp(22), 0, dp(8)) })
        fun infoRow(label: String, value: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(Color.rgb(46, 46, 46), 10) }
            row.addView(text(label.uppercase(Locale.ROOT), 10, WallUi.subtle, true))
            row.addView(text(value, 13, WallUi.ink, false).apply { setPadding(0, dp(3), 0, 0) })
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        infoRow("Package name", app.packageName)
        infoRow("Version · size", "${info.versionName ?: "unknown"}  ·  ${formatBytes(File(app.sourceDir).length())}")
        infoRow("Installed APK", app.sourceDir)
        root.addView(text("Export location: /storage/emulated/0/APK Wall/apks/", 11, WallUi.muted, false).apply { setPadding(dp(2), dp(8), dp(2), dp(14)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = actionButton("CANCEL", WallUi.surfaceSoft) { dialog.dismiss() }
        val extract = actionButton("EXTRACT APK", WallUi.tealDark) { dialog.dismiss(); exportInstalledApk(app) }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) }); actions.addView(extract, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) }); root.addView(actions)
        dialog.setContentView(root); dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92).toInt(), -2)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2fM", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0fK", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun showHookGenerator() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WallUi.background) }
        page.addView(topBar("Hook Generator", false) { buildHome() }, LinearLayout.LayoutParams(-1, dp(68)))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(30)) }
        body.addView(text("Generate C++ hook templates", 24, WallUi.ink, true))
        body.addView(text("Native version of the pasted generator. Choose a type and feature, fill the offsets, then copy the ready template.", 13, WallUi.muted, false).apply { setPadding(0, dp(5), 0, dp(18)) })
        val type = spinnerField(body, "RETURN TYPE", listOf("bool", "int", "float", "void"))
        val feature = spinnerField(body, "FEATURE", listOf("toggle", "seekbar", "inputvalue"))
        val update = spinnerField(body, "WITH UPDATE", listOf("no", "yes"))
        val voidType = spinnerField(body, "VOID ARGUMENT TYPE", listOf("bool", "int", "float"))
        val inputName = textField(body, "INPUT NAME", "godMode")
        val offset = textField(body, "OFFSET", "123456")
        val offsetUpdate = textField(body, "UPDATE OFFSET (void + update only)", "654321")
        val value = textField(body, "VALUE (toggle / void update)", "999")
        val outputCard = card(WallUi.surfaceRaised)
        val outputBox = column(10)
        outputBox.addView(label("GENERATED C++"))
        val output = text("", 12, WallUi.ink, false).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.rgb(10, 10, 10))
        }
        outputBox.addView(output, LinearLayout.LayoutParams(-1, dp(360)))
        outputCard.addView(outputBox); body.addView(outputCard)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        val generate = actionButton("Generate code", WallUi.tealDark) { renderHookCode(type, feature, update, voidType, inputName, offset, offsetUpdate, value, output) }
        val copy = actionButton("Copy", WallUi.surfaceSoft) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("APK Wall hook", output.text)); Toast.makeText(this, "Generated code copied", Toast.LENGTH_SHORT).show() }
        actions.addView(generate, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) }); actions.addView(copy, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
        body.addView(actions)
        body.addView(text("This tool generates templates only. Review offsets, library names, and signatures before compiling.", 11, WallUi.subtle, false).apply { setPadding(dp(4), dp(12), dp(4), 0) })
        scroll.addView(body); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(page); insets(page)
        renderHookCode(type, feature, update, voidType, inputName, offset, offsetUpdate, value, output)
    }

    private fun spinnerField(parent: LinearLayout, title: String, values: List<String>): Spinner {
        parent.addView(label(title).apply { setPadding(dp(2), dp(10), 0, dp(5)) })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
            background = rounded(WallUi.surfaceSoft, 10)
        }
        parent.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(3) })
        return spinner
    }

    private fun textField(parent: LinearLayout, title: String, hint: String): EditText {
        parent.addView(label(title).apply { setPadding(dp(2), dp(10), 0, dp(5)) })
        val field = EditText(this).apply {
            this.hint = hint; textSize = 15f; setSingleLine(true); setTextColor(WallUi.ink); setHintTextColor(WallUi.subtle); setPadding(dp(14), 0, dp(14), 0); background = rounded(WallUi.surfaceSoft, 10)
            inputType = if (title.contains("OFFSET") || title.contains("VALUE")) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT
        }
        parent.addView(field, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(3) })
        return field
    }

    private fun renderHookCode(type: Spinner, feature: Spinner, update: Spinner, voidType: Spinner, input: EditText, offset: EditText, offsetUpdate: EditText, value: EditText, output: TextView) {
        output.text = generateHookCode(type.selectedItem.toString(), feature.selectedItem.toString(), update.selectedItem.toString(), voidType.selectedItem.toString(), input.text.toString().trim().ifBlank { "featureValue" }, offset.text.toString().trim().ifBlank { "0x0" }, offsetUpdate.text.toString().trim().ifBlank { "0x0" }, value.text.toString().trim().ifBlank { "0" })
    }

    private fun generateHookCode(type: String, feature: String, update: String, voidType: String, name: String, offset: String, updateOffset: String, value: String): String {
        val floatValue = if (value.endsWith(".0")) value else "$value.0"
        if (type == "void") {
            val arg = when (voidType) { "int" -> "int value"; "float" -> "float value"; else -> "bool value" }
            val literal = when (voidType) { "int" -> value; "float" -> floatValue; else -> "true" }
            return if (update == "yes") """//==============| VOID ${voidType.uppercase(Locale.ROOT)} WITH UPDATE |==============//

bool $name;

void (*$name)(void *instance, $arg);
void (*old_$name)(void *instance);
void $name(void *instance, ${arg}) {
    if (instance != NULL) {
        if ($name) {
            $name(instance, $literal);
            return;
        }
    }
    return old_$name(instance);
}

HOOK_LIB("libname.so","$updateOffset",$name,old_$name);
$name = (void (*)(void *, ${voidType}))getAbsoluteAddress(targetLibName,0x$offset);
OBFUSCATE("0_Toggle_hackname"),
case 0:
    $name = boolean;
    break;""" else """//==============| VOID ${voidType.uppercase(Locale.ROOT)} WITHOUT UPDATE |==============//

bool $name;

void (*old_$name)(void *instance, $arg);
void $name(void *instance, ${arg}) {
    if (instance != NULL) {
        if ($name) {
            old_$name(instance, $literal);
            return;
        }
    }
    return old_$name(instance);
}

HOOK_LIB("libname.so","$offset",$name,old_$name);
OBFUSCATE("0_Toggle_hackname"),
case 0:
    $name = boolean;
    break;"""
        }
        return when (type) {
            "bool" -> """//===============| REGION VARIABLE BOOL |===============//

bool $name = false;

bool (*old_$name)(void *instance);
bool $name(void *instance) {
    if (instance != NULL) {
        if ($name) return true;
    }
    return old_$name(instance);
}

HOOK_LIB("libname.so","$offset",$name,old_$name);
OBFUSCATE("0_Toggle_hackname"),
case 0:
    $name = boolean;
    break;"""
            "int", "float" -> {
                val cType = type
                val result = if (feature == "toggle") "return ${if (type == "float") floatValue else value};" else "return $name;"
                val marker = when (feature) { "seekbar" -> "0_SeekBar_hackname_1_$value"; "inputvalue" -> "0_InputValue_hackname"; else -> "0_Toggle_hackname" }
                """//===============| REGION VARIABLE ${type.uppercase(Locale.ROOT)} ${feature.uppercase(Locale.ROOT)} |===============//

$cType $name${if (feature == "toggle") " = false" else ""};

$cType (*old_$name)(void *instance);
$cType $name(void *instance) {
    if (instance != NULL && $name) {
        $result
    }
    return old_$name(instance);
}

HOOK_LIB("libname.so","$offset",$name,old_$name);
OBFUSCATE("$marker"),
case 0:
    $name = value;
    break;"""
            }
            else -> "Unsupported type"
        }
    }

    private fun exportInstalledApk(app: ApplicationInfo) {
        if (!hasStorageAccess()) {
            selectedInstalledApp = app
            pendingExport = true
            requestStorageAccess()
            return
        }
        selectedInstalledApp = app
        val source = File(app.sourceDir)
        if (::extractorButton.isInitialized) { extractorButton.isEnabled = false; extractorButton.text = "Exporting…" }
        ioExecutor.execute {
            try {
                val root = File(Environment.getExternalStorageDirectory(), "APK Wall/apks")
                if (!root.exists() && !root.mkdirs()) throw IllegalStateException("Could not create /storage/emulated/0/APK Wall/apks/")
                val label = app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val destination = File(root, "$label.apk")
                FileInputStream(source).use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
                runOnUiThread { if (::extractorButton.isInitialized) { extractorButton.isEnabled = true; extractorButton.text = "Browse installed apps  →" }; Toast.makeText(this, "APK saved to ${destination.path}", Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                runOnUiThread { if (::extractorButton.isInitialized) { extractorButton.isEnabled = true; extractorButton.text = "Browse installed apps  →" }; Toast.makeText(this, "Export failed: ${error.message ?: "storage error"}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun hasStorageAccess(): Boolean = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            Toast.makeText(this, "Allow APK Wall storage access, then return here", Toast.LENGTH_LONG).show()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingExport && hasStorageAccess()) {
            pendingExport = false
            selectedInstalledApp?.let { exportInstalledApk(it) }
        }
    }

    private fun buildProtector() {
        drawer = DrawerLayout(this).apply { setBackgroundColor(WallUi.background) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WallUi.background) }
        page.addView(topBar("APK Wall", true) { drawer.openDrawer(GravityCompat.START) }, LinearLayout.LayoutParams(-1, dp(72)))
        homeScroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(28))
        }
        homeScroll.addView(body)
        page.addView(homeScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val sticky = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(15))
            setBackgroundColor(WallUi.surface)
            elevation = dp(14).toFloat()
        }
        startButton = actionButton("Protect selected APK  →", WallUi.tealDark) { startProtection() }
        sticky.addView(startButton, LinearLayout.LayoutParams(-1, dp(54)))
        sticky.addView(text("Offline processing  •  output saved to Downloads / APK Wall", 11, WallUi.subtle, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        page.addView(sticky)
        drawer.addView(page, DrawerLayout.LayoutParams(-1, -1))
        navigation = NavigationView(this).apply {
            setBackgroundColor(WallUi.surface)
            itemTextColor = ColorStateList.valueOf(WallUi.ink)
            itemIconTintList = ColorStateList.valueOf(WallUi.teal)
            layoutParams = DrawerLayout.LayoutParams(dp(316), -1, Gravity.START)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(24), dp(42), dp(20), dp(24))
            setBackgroundColor(WallUi.surfaceRaised)
        }
        header.addView(text("APK WALL", 21, WallUi.ink, true))
        header.addView(text("Secure locally. Ship confidently.", 13, WallUi.muted, false).apply { setPadding(0, dp(7), 0, 0) })
        navigation.addHeaderView(header)
        navigation.menu.add(0, 1, 0, "Dashboard").setIcon(android.R.drawable.ic_menu_view)
        navigation.menu.add(0, 2, 1, "About APK Wall").setIcon(android.R.drawable.ic_menu_info_details)
        navigation.menu.add(0, 3, 2, "Credits · Krishna").setIcon(android.R.drawable.ic_menu_share)
        navigation.menu.add(0, 4, 3, "Protection methods").setIcon(android.R.drawable.ic_menu_manage)
        navigation.setNavigationItemSelectedListener { item ->
            drawer.closeDrawer(navigation)
            when (item.itemId) { 1 -> buildHome(); 2 -> showAbout(); 3 -> openCredits(); 4 -> showMethods() }
            true
        }
        drawer.addView(navigation)
        setContentView(drawer)
        insets(drawer)
        fillHome(body)
        handler.removeCallbacks(watcher)
        handler.post(watcher)
    }

    private fun fillHome(body: LinearLayout) {
        body.addView(text("Protect your APK", 29, WallUi.ink, true))
        body.addView(text("A focused, offline workspace for wrapping and signing Android packages.", 14, WallUi.muted, false).apply {
            setPadding(0, dp(7), 0, dp(18))
        })
        val fileCard = card(WallUi.surfaceRaised)
        val fileBox = column()
        fileBox.addView(label("STEP 01  /  INPUT"))
        selectedFile = text("No APK selected", 16, WallUi.ink, true).apply { setPadding(0, dp(8), 0, dp(4)) }
        fileBox.addView(selectedFile)
        fileBox.addView(text("Choose one base APK from your device. It stays local.", 12, WallUi.muted, false).apply { setPadding(0, 0, 0, dp(13)) })
        fileBox.addView(actionButton("Choose APK file", WallUi.tealDark) { selectApk() })
        fileCard.addView(fileBox)
        body.addView(fileCard)

        body.addView(sectionTitle("Live protection status", "STEP 02  /  PROCESS"))
        val progressCard = card(WallUi.surfaceRaised)
        val progressBox = column()
        val progressHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        progressHeader.addView(text("Protection pipeline", 16, WallUi.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        percent = text("0%", 14, WallUi.teal, true)
        progressHeader.addView(percent)
        progressBox.addView(progressHeader)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(WallUi.teal)
            progressBackgroundTintList = ColorStateList.valueOf(WallUi.line)
        }
        progressBox.addView(progress, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(14) })
        stepRows = listOf("Parsing APK" to "Package and manifest", "Processing XML" to "Runtime loader setup", "Encrypting DEX" to "Authenticated payloads", "Signing APK" to "Local key verification", "Save file" to "Downloads / APK Wall").map { (name, subtitle) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
            val mark = text("○", 21, WallUi.subtle, true).apply { gravity = Gravity.CENTER; minWidth = dp(31) }
            val labels = column(0)
            labels.addView(text(name, 13, WallUi.ink, true))
            labels.addView(text(subtitle, 11, WallUi.subtle, false).apply { setPadding(0, dp(2), 0, 0) })
            row.addView(mark, LinearLayout.LayoutParams(dp(34), -2)); row.addView(labels)
            progressBox.addView(row)
            mark to text(name, 12, WallUi.ink, false)
        }
        detail = text("Select an APK to begin.", 12, WallUi.muted, false).apply { setPadding(0, dp(14), 0, 0) }
        progressBox.addView(detail)
        progressCard.addView(progressBox); body.addView(progressCard)

        body.addView(sectionTitle("Always-on protection", "STEP 03  /  CORE"))
        val core = card(WallUi.surfaceRaised); val coreBox = column()
        coreBox.addView(methodRow("AES-256-GCM DEX wrapper", "ACTIVE", true, "Authenticates and encrypts every standard classes*.dex payload, then loads it from private app storage at startup."))
        coreBox.addView(text("Output is rebuilt and signed with APK Wall's local Krishna key.", 12, WallUi.muted, false).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        core.addView(coreBox); body.addView(core)

        body.addView(sectionTitle("Optional transforms", "CUSTOMIZE"))
        val transforms = card(WallUi.surfaceRaised); val transformsBox = column()
        encryptStringsSwitch = optionSwitch(transformsBox, "Encrypt DEX string literals", "AES-GCM ciphertext plus runtime decode calls.", false)
        encryptAssetsSwitch = optionSwitch(transformsBox, "Encrypt text/config assets", "Supports safe direct AssetManager.open paths.", false)
        obfuscateResourcesSwitch = optionSwitch(transformsBox, "Obfuscate resource names", "Renames app-owned resources while keeping IDs.", false)
        transforms.addView(transformsBox); body.addView(transforms)

        body.addView(sectionTitle("Release hardening", "OPTIONAL"))
        val hardening = card(WallUi.surfaceRaised); val hardeningBox = column()
        disableDebugSwitch = optionSwitch(hardeningBox, "Disable APK debug mode", "Sets android:debuggable=false. Enabled by default.", true)
        disableBackupSwitch = optionSwitch(hardeningBox, "Disable Android backup", "Sets android:allowBackup=false.", false)
        requireSignatureSwitch = optionSwitch(hardeningBox, "Require valid input signature", "Stop when the original signature is invalid.", false)
        hardening.addView(hardeningBox); body.addView(hardening)

        body.addView(sectionTitle("Launch checks", "ADVANCED"))
        val runtime = card(WallUi.surfaceRaised); val runtimeBox = column()
        runtimeBox.addView(text("Best-effort heuristics. Keep these off unless the target app has been tested on real devices.", 12, WallUi.amber, false).apply { setPadding(dp(3), 0, dp(3), dp(8)) })
        blockRootSwitch = optionSwitch(runtimeBox, "Block likely rooted devices", "Checks common su, test-keys and Magisk markers.", false)
        blockEmulatorSwitch = optionSwitch(runtimeBox, "Block Android emulators", "Checks common emulator build properties.", false)
        blockDebuggerSwitch = optionSwitch(runtimeBox, "Block an attached debugger", "Checks Debug.isDebuggerConnected() at startup.", false)
        blockInstrumentationSwitch = optionSwitch(runtimeBox, "Block Frida/Xposed markers", "Checks known process-map and file markers.", false)
        runtime.addView(runtimeBox); body.addView(runtime)
        body.addView(text("Android 10+  •  Offline by design  •  Split APKs unsupported\nProtection increases analysis effort, but runtime code must still be decrypted to execute.", 12, WallUi.subtle, false).apply { setPadding(dp(4), dp(16), dp(4), dp(6)) })
    }

    private fun methodRow(name: String, badge: String, active: Boolean, explanation: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(if (active) "●" else "○", 15, if (active) WallUi.teal else WallUi.subtle, true).apply { gravity = Gravity.CENTER; minWidth = dp(30) })
        top.addView(text(name, 15, WallUi.ink, active), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(chip(badge, if (active) WallUi.teal else WallUi.subtle))
        row.addView(top)
        row.addView(text(explanation, 12, WallUi.muted, false).apply { setPadding(dp(30), dp(5), dp(4), 0) })
        return row
    }

    private fun optionSwitch(parent: LinearLayout, title: String, explanation: String, checked: Boolean): SwitchMaterial {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(3), 0, dp(8)) }
        val toggle = SwitchMaterial(this).apply {
            text = title; textSize = 14f; setTextColor(WallUi.ink); isChecked = checked
            thumbTintList = ColorStateList.valueOf(WallUi.teal); trackTintList = ColorStateList.valueOf(WallUi.line)
            minimumHeight = dp(44)
        }
        group.addView(toggle)
        group.addView(text(explanation, 12, WallUi.muted, false).apply { setPadding(dp(4), 0, dp(4), 0) })
        parent.addView(group); return toggle
    }

    private fun startProtection() {
        val source = apkUri
        if (source == null) { Toast.makeText(this, "Choose an APK file first", Toast.LENGTH_SHORT).show(); return }
        homeScroll.smoothScrollTo(0, 0)
        val destination = createDownloadsOutput(source)
        if (destination == null) { Toast.makeText(this, "Could not create an output in Downloads", Toast.LENGTH_LONG).show(); return }
        val prefs = getSharedPreferences(ApkProtectionService.PREFS, MODE_PRIVATE)
        prefs.edit().putBoolean("active", true).putInt("progress", 1).putString("message", "Queued for local protection…").apply()
        val intent = Intent(this, ApkProtectionService::class.java).apply {
            putExtra(ApkProtectionService.EXTRA_APK_URI, source.toString())
            putExtra(ApkProtectionService.EXTRA_OUTPUT_URI, destination.toString())
            putExtra(ApkProtectionService.EXTRA_DISABLE_DEBUG, disableDebugSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_DISABLE_BACKUP, disableBackupSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_REQUIRE_INPUT_SIGNATURE, requireSignatureSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_ENCRYPT_STRINGS, encryptStringsSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_ENCRYPT_ASSETS, encryptAssetsSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_OBFUSCATE_RESOURCES, obfuscateResourcesSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_BLOCK_ROOT, blockRootSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_BLOCK_EMULATOR, blockEmulatorSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_BLOCK_DEBUGGER, blockDebuggerSwitch.isChecked)
            putExtra(ApkProtectionService.EXTRA_BLOCK_INSTRUMENTATION, blockInstrumentationSwitch.isChecked)
        }
        try { ContextCompat.startForegroundService(this, intent); startButton.isEnabled = false }
        catch (e: Exception) { prefs.edit().putBoolean("active", false).putString("message", "Could not start: ${e.message ?: "system restriction"}").apply(); contentResolver.delete(destination, null, null) }
    }

    private fun createDownloadsOutput(uri: Uri): Uri? = try {
        val name = queryName(uri)?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.take(70).orEmpty().ifBlank { "protected-app" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${name}_krishna_protected.apk")
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/APK Wall")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (_: Exception) { null }

    private fun selectApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/vnd.android.package-archive"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_APK) } catch (_: Exception) { Toast.makeText(this, "File picker unavailable", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("System document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_APK && requestCode != REQUEST_EXTRACTOR || resultCode != RESULT_OK) return
        val uri = data?.data ?: return; val name = queryName(uri) ?: "Selected APK"
        if (!name.lowercase(Locale.ROOT).endsWith(".apk")) { Toast.makeText(this, "Choose an APK file", Toast.LENGTH_SHORT).show(); return }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        if (requestCode == REQUEST_EXTRACTOR) {
            extractorUri = uri
            extractorFileLabel.text = "$name\n${querySize(uri)}"
            extractorButton.isEnabled = true
        } else {
            apkUri = uri; selectedFile.text = "$name\n${querySize(uri)}"
            getSharedPreferences(ApkProtectionService.PREFS, MODE_PRIVATE).edit().putInt("progress", 0).putString("message", "Ready · choose optional transforms below").putBoolean("active", false).apply()
        }
    }

    private fun updateSteps(message: String, value: Int) {
        if (!::stepRows.isInitialized) return
        val failed = message.contains("Protection failed", true)
        val current = when { value >= 95 -> 4; value >= 55 -> 3; value >= 26 -> 2; value >= 22 -> 1; value >= 3 -> 0; else -> -1 }
        stepRows.forEachIndexed { index, pair ->
            pair.first.text = when { value >= 100 -> "✓"; index < current -> "✓"; index == current && failed -> "×"; index == current -> "●"; else -> "○" }
            pair.first.setTextColor(when { value >= 100 || index < current -> WallUi.green; index == current && failed -> WallUi.red; index == current -> WallUi.teal; else -> WallUi.subtle })
        }
    }

    private fun colorProgress(message: String) {
        detail.setTextColor(when { message.contains("failed", true) || message.contains("could not", true) -> WallUi.red; message.contains("warning", true) || message.contains("skipped", true) -> WallUi.amber; message.contains("Completed", true) -> WallUi.green; else -> WallUi.muted })
    }

    private fun topBar(title: String, menuIcon: Boolean, action: () -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(7), dp(18), dp(0)); setBackgroundColor(WallUi.background) }
        val nav = TextView(this).apply { text = if (menuIcon) "☰" else "‹"; textSize = if (menuIcon) 22f else 35f; gravity = Gravity.CENTER; setTextColor(WallUi.ink); contentDescription = if (menuIcon) "Open navigation drawer" else "Back to home"; isClickable = true; setOnClickListener { action() } }
        bar.addView(nav, LinearLayout.LayoutParams(dp(52), -1))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        titleBox.addView(text(title, 19, WallUi.ink, true))
        titleBox.addView(text("OFFLINE SECURITY WORKSPACE", 9, WallUi.teal, true).apply { letterSpacing = .12f; setPadding(0, dp(2), 0, 0) })
        bar.addView(titleBox)
        return bar
    }

    private fun showMethods() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WallUi.background) }
        page.addView(topBar("Protection methods", false) { buildHome() }, LinearLayout.LayoutParams(-1, dp(72)))
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(28)) }
        content.addView(text("Know exactly what ships", 27, WallUi.ink, true))
        content.addView(text("Transparent capabilities, with no pretend switches.", 14, WallUi.muted, false).apply { setPadding(0, dp(7), 0, dp(15)) })
        fun addMethod(name: String, status: String, active: Boolean, explanation: String) { val c = card(WallUi.surfaceRaised); val b = column(); b.addView(methodRow(name, status, active, explanation)); c.addView(b); content.addView(c) }
        content.addView(sectionTitle("Implemented in APK Wall", "AVAILABLE"))
        addMethod("AES-256-GCM DEX wrapper", "ALWAYS ON", true, "Every standard classes*.dex entry is authenticated, encrypted and loaded by the runtime bootstrap.")
        addMethod("DEX string-literal encryption", "OPTIONAL", true, "Encrypts eligible 4+ character literals and inserts runtime decode calls.")
        addMethod("Small text/config asset encryption", "OPTIONAL", true, "Encrypts supported assets and redirects direct AssetManager.open calls when safe.")
        addMethod("Resource-name obfuscation", "OPTIONAL", true, "Renames eligible resource-table names while retaining numeric IDs.")
        addMethod("Manifest hardening", "PARTIAL", true, "Can disable debug mode and optionally Android backup behavior.")
        addMethod("Signing and output verification", "ALWAYS ON", true, "Rebuilds, signs with the local Krishna key and verifies the output.")
        addMethod("Root / emulator / debugger checks", "OPTIONAL", true, "Best-effort launch heuristics that can be bypassed or false-positive.")
        content.addView(sectionTitle("Requires source build or licensed SDK", "NOT GENERIC"))
        addMethod("R8 / ProGuard and resource shrinking", "SOURCE BUILD", false, "Requires the original build graph, bytecode and keep rules.")
        addMethod("Control-flow obfuscation / NDK conversion", "SOURCE BUILD", false, "Requires app-specific compiler or native integration.")
        addMethod("Virtualization, white-box crypto and commercial shielding", "VENDOR SDK", false, "Requires a purpose-built licensed engine and compatibility testing.")
        addMethod("Play Integrity / server-side checks", "OMITTED", false, "Needs backend integration and is intentionally outside this offline tool.")
        scroll.addView(content); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(page); insets(page)
    }

    private fun showAbout() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WallUi.background) }
        page.addView(topBar("About APK Wall", false) { buildHome() }, LinearLayout.LayoutParams(-1, dp(72)))
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        content.addView(text("Built for careful protection", 27, WallUi.ink, true))
        content.addView(text("APK Wall · Krishna", 15, WallUi.teal, true).apply { setPadding(0, dp(7), 0, dp(17)) })
        val info = card(WallUi.surfaceRaised); val copy = column()
        copy.addView(text("Local by default", 18, WallUi.ink, true))
        copy.addView(text("APK Wall processes the selected package on-device. It wraps standard DEX files with AES-256-GCM, applies only the switches you select, rebuilds the package, and signs/verifies the result with a device-local key.", 14, WallUi.muted, false).apply { setPadding(0, dp(8), 0, 0) })
        copy.addView(text("Compatibility notes", 18, WallUi.ink, true).apply { setPadding(0, dp(19), 0, 0) })
        copy.addView(text("APK Wall requires Android 10 / API 29+. Protected output supports Android 9 / API 28+. Split APKs are unsupported. Re-signing replaces the original publisher identity, so the output normally cannot update the original installation. Test on representative devices before distribution.", 14, WallUi.muted, false).apply { setPadding(0, dp(8), 0, 0) })
        info.addView(copy); content.addView(info)
        content.addView(actionButton("Open Krishna credits  ↗", WallUi.tealDark) { openCredits() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(15) })
        scroll.addView(content); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(page); insets(page)
    }

    private fun openCredits() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/KRISHNA1_EXE"))) } catch (_: Exception) { Toast.makeText(this, "https://t.me/KRISHNA1_EXE", Toast.LENGTH_LONG).show() }
    }

    private fun queryName(uri: Uri): String? = try { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }
    private fun querySize(uri: Uri): String {
        val bytes = try { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L } catch (_: Exception) { -1L }
        if (bytes < 0) return "Size unavailable"
        return if (bytes >= 1024L * 1024L) String.format(Locale.US, "%.2f MB", bytes / 1048576.0) else "${bytes / 1024} KB"
    }

    private fun insets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, wi -> val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom); wi }
        ViewCompat.requestApplyInsets(view)
    }
    private fun sectionTitle(value: String, eyebrow: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(18), 0, dp(8))
        addView(text(eyebrow, 10, WallUi.teal, true).apply { letterSpacing = .12f })
        addView(text(value, 19, WallUi.ink, true).apply { setPadding(0, dp(4), 0, 0) })
    }
    private fun card(color: Int) = MaterialCardView(this).apply {
        radius = dp(18).toFloat(); cardElevation = 0f; setCardBackgroundColor(color); strokeColor = WallUi.line; strokeWidth = dp(1)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    private fun column(padding: Int = 17) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
    private fun label(value: String) = text(value, 10, WallUi.teal, true).apply { letterSpacing = .1f }
    private fun chip(value: String, color: Int) = TextView(this).apply { text = value; textSize = 10f; setTextColor(color); setTypeface(typeface, Typeface.BOLD); setPadding(dp(9), dp(5), dp(9), dp(5)); background = rounded(if (color == WallUi.teal) Color.rgb(22, 71, 72) else Color.rgb(38, 55, 76), 20) }
    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = true; if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun actionButton(value: String, color: Int, action: () -> Unit) = Button(this).apply { text = value; isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(if (color == WallUi.tealDark) Color.WHITE else WallUi.background); background = rounded(color, 15); stateListAnimator = null; setOnClickListener { action() } }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    override fun onDestroy() { handler.removeCallbacks(watcher); ioExecutor.shutdownNow(); super.onDestroy() }
    companion object { private const val REQUEST_APK = 4101; private const val REQUEST_EXTRACTOR = 4102; private const val REQUEST_STORAGE = 4103 }
}
