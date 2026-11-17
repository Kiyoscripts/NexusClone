package com.kiyoscripts.nexusclone.view.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash and forwarder.
 *
 * This used to run a full installed-package enumeration plus an icon decode for every
 * package before forwarding, on the main thread, and then immediately finish. The scan
 * raced the Activity teardown and its result was thrown away: the Apps tab loads the list
 * lazily when it is actually opened. Nothing is scanned here any more.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jump()
    }

    private fun jump() {
        MainActivity.start(this)
        finish()
    }
}
