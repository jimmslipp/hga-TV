package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hga.media.R
import com.hga.media.data.PlaylistType
import com.hga.media.data.Profile
import com.hga.media.data.Repo
import com.hga.media.data.XtreamClient
import com.hga.media.util.L
import com.hga.media.util.normaliseBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Playlist setup. Accepts either a set of Xtream Codes details (server,
 * username, password) or a plain M3U link. The line is tested before it is
 * saved so a typo is caught here rather than on a black screen in a venue.
 */
class LoginActivity : AppCompatActivity() {

    private var type = PlaylistType.XTREAM

    private lateinit var tabXtream: TextView
    private lateinit var tabM3u: TextView
    private lateinit var groupXtream: LinearLayout
    private lateinit var groupM3u: LinearLayout
    private lateinit var inProfile: EditText
    private lateinit var inServer: EditText
    private lateinit var inUser: EditText
    private lateinit var inPass: EditText
    private lateinit var inM3u: EditText
    private lateinit var inEpg: EditText
    private lateinit var status: TextView
    private lateinit var btnConnect: TextView
    private lateinit var btnCancel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        UiKit.goFullScreen(this)

        tabXtream = findViewById(R.id.tabXtream)
        tabM3u = findViewById(R.id.tabM3u)
        groupXtream = findViewById(R.id.groupXtream)
        groupM3u = findViewById(R.id.groupM3u)
        inProfile = findViewById(R.id.inProfile)
        inServer = findViewById(R.id.inServer)
        inUser = findViewById(R.id.inUser)
        inPass = findViewById(R.id.inPass)
        inM3u = findViewById(R.id.inM3u)
        inEpg = findViewById(R.id.inEpg)
        status = findViewById(R.id.loginStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnCancel = findViewById(R.id.btnCancel)

        Repo.prefs.profile?.let { existing ->
            type = existing.type
            inProfile.setText(existing.name)
            inServer.setText(existing.server)
            inUser.setText(existing.username)
            inPass.setText(existing.password)
            inM3u.setText(existing.m3uUrl)
            inEpg.setText(existing.epgUrl)
        }

        tabXtream.setOnClickListener { setType(PlaylistType.XTREAM) }
        tabM3u.setOnClickListener { setType(PlaylistType.M3U) }
        btnCancel.setOnClickListener { onBackPressedCompat() }
        btnConnect.setOnClickListener { attemptConnect() }

        setType(type)
        inProfile.requestFocus()
    }

    private fun setType(newType: String) {
        type = newType
        val xtream = newType == PlaylistType.XTREAM
        groupXtream.visibility = if (xtream) View.VISIBLE else View.GONE
        groupM3u.visibility = if (xtream) View.GONE else View.VISIBLE
        tabXtream.isSelected = xtream
        tabM3u.isSelected = !xtream
    }

    private fun buildProfile(): Profile = Profile(
        id = "default",
        name = inProfile.text.toString().trim().ifBlank { "My Playlist" },
        type = type,
        server = inServer.text.toString().normaliseBaseUrl(),
        username = inUser.text.toString().trim(),
        password = inPass.text.toString().trim(),
        m3uUrl = inM3u.text.toString().trim(),
        epgUrl = inEpg.text.toString().trim()
    )

    private fun attemptConnect() {
        val profile = buildProfile()

        val problem = when {
            type == PlaylistType.XTREAM && profile.server.isBlank() -> "Enter the server address"
            type == PlaylistType.XTREAM && profile.username.isBlank() -> "Enter your username"
            type == PlaylistType.XTREAM && profile.password.isBlank() -> "Enter your password"
            type == PlaylistType.M3U && profile.m3uUrl.isBlank() -> "Paste your M3U link"
            else -> null
        }
        if (problem != null) {
            status.text = problem
            return
        }

        btnConnect.isEnabled = false
        status.text = "Checking your details…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (type == PlaylistType.XTREAM) {
                        val auth = XtreamClient(profile).authenticate()
                        "Connected. Account ${auth.status}" +
                                (if (auth.expiry.isNotBlank()) ", ${friendlyExpiry(auth.expiry)}" else "") +
                                ", ${auth.activeConnections}/${auth.maxConnections} connections in use"
                    } else {
                        val parsed = com.hga.media.data.M3uParser.fetchAndParse(profile.m3uUrl)
                        if (parsed.channels.isEmpty()) throw IllegalStateException("That link returned no channels")
                        "Connected. Found ${parsed.channels.size} entries"
                    }
                }
            }

            btnConnect.isEnabled = true
            result.onSuccess { message ->
                status.text = message
                Repo.prefs.profile = profile
                Repo.clearCache()
                startActivity(Intent(this@LoginActivity, SplashActivity::class.java))
                finish()
            }.onFailure { error ->
                L.w("Login failed: ${error.message}")
                status.text = friendlyError(error)
            }
        }
    }

    private fun friendlyExpiry(raw: String): String = try {
        val seconds = raw.toLong()
        val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
            .format(java.util.Date(seconds * 1000))
        "expires $date"
    } catch (e: Exception) { "" }

    private fun friendlyError(error: Throwable): String {
        val message = error.message ?: "Unknown problem"
        return when {
            message.contains("Unable to resolve host", true) ->
                "Cannot find that server. Check the address and that this device is online."
            message.contains("ECONNREFUSED", true) || message.contains("failed to connect", true) ->
                "The server refused the connection. Check the port number."
            message.contains("timed out", true) ->
                "The server did not answer in time. It may be busy or blocked."
            message.contains("401") || message.contains("403") || message.contains("rejected", true) ->
                "Username or password not accepted by the provider."
            else -> message
        }
    }

    @Suppress("DEPRECATION")
    private fun onBackPressedCompat() {
        if (Repo.prefs.hasProfile) finish() else finishAffinity()
    }
}
