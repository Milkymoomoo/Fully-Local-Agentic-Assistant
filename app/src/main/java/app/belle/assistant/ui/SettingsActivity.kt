package app.belle.assistant.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Entry point used by the avatar menu / notification; opens the Settings tab of the main screen. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "settings")
            .putExtra("page", intent.getStringExtra("page") ?: "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
