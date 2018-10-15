package com.pr0gramm.app.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.jakewharton.rxbinding.widget.textChanges
import com.pr0gramm.app.R
import com.pr0gramm.app.RequestCodes
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.services.ThemeHelper
import com.pr0gramm.app.services.ThemeHelper.primaryColorDark
import com.pr0gramm.app.services.Track
import com.pr0gramm.app.services.UserService
import com.pr0gramm.app.sync.SyncJob
import com.pr0gramm.app.ui.base.BaseAppCompatActivity
import com.pr0gramm.app.ui.dialogs.ErrorDialogFragment.Companion.showErrorString
import com.pr0gramm.app.ui.fragments.withBusyDialog
import com.pr0gramm.app.util.*
import com.trello.rxlifecycle.android.ActivityEvent
import kotterknife.bindView
import org.kodein.di.erased.instance
import retrofit2.HttpException
import rx.Observable

/**
 */
class LoginActivity : BaseAppCompatActivity("LoginActivity") {
    private val userService: UserService by instance()
    private val prefs: SharedPreferences by instance()

    private val usernameView: EditText by bindView(R.id.username)
    private val passwordView: EditText by bindView(R.id.password)
    private val submitView: Button by bindView(R.id.login)

    public override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.theme.whiteAccent)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_login)

        // restore last username
        val defaultUsername = prefs.getString(PREF_USERNAME, "")
        if (!defaultUsername.isNullOrEmpty()) {
            usernameView.setText(defaultUsername)
        }

        submitView.setOnClickListener { onLoginClicked() }

        find<View>(R.id.register).setOnClickListener { onRegisterClicked() }
        find<View>(R.id.password_recovery).setOnClickListener { onPasswordRecoveryClicked() }

        updateActivityBackground()

        Observable.combineLatest(
                usernameView.textChanges().map { it.trim().length },
                passwordView.textChanges().map { it.trim().length },
                { username, password -> username * password })
                .subscribe { value -> submitView.isEnabled = value > 0 }

    }

    private fun updateActivityBackground() {
        val style = ThemeHelper.theme.whiteAccent

        @DrawableRes
        val drawableId = theme.obtainStyledAttributes(style, R.styleable.AppTheme).use {
            it.getResourceId(R.styleable.AppTheme_loginBackground, 0)
        }

        if (drawableId == 0)
            return

        val fallbackColor = ContextCompat.getColor(this, primaryColorDark)
        val background = createBackgroundDrawable(drawableId, fallbackColor)
        ViewCompat.setBackground(findViewById(R.id.content), background)
    }

    private fun createBackgroundDrawable(drawableId: Int, fallbackColor: Int): Drawable {
        val background: Drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            background = WrapCrashingDrawable(fallbackColor,
                    ResourcesCompat.getDrawable(resources, drawableId, theme)!!)
        } else {
            background = ColorDrawable(fallbackColor)
        }
        return background
    }


    private fun enableView(enable: Boolean) {
        usernameView.isEnabled = enable
        passwordView.isEnabled = enable
        submitView.isEnabled = enable
    }

    private fun onLoginClicked() {
        val username = usernameView.text.toString()
        val password = passwordView.text.toString()

        if (username.isEmpty()) {
            usernameView.error = getString(R.string.must_not_be_empty)
            return
        }

        if (password.isEmpty()) {
            passwordView.error = getString(R.string.must_not_be_empty)
            return
        }

        // store last username
        prefs.edit().putString(PREF_USERNAME, username).apply()

        userService.login(username, password)
                .compose(bindUntilEventAsync(ActivityEvent.DESTROY))
                .withBusyDialog(this, R.string.login_please_wait)
                .flatMap { progress -> progress.login.justObservable() }
                .interceptLoginFailures()
                .doOnSubscribe { enableView(false) }
                .doOnError { enableView(true) }
                .subscribeWithErrorHandling { handleLoginResult(it) }
    }

    private fun Observable<Api.Login>.interceptLoginFailures(): Observable<LoginResult> {
        return this.
                map { response ->
                    when {
                        response.success -> LoginResult.Success()
                        response.banInfo != null -> LoginResult.Banned(response.banInfo)
                        else -> LoginResult.Failure()
                    }
                }
                .onErrorResumeNext { err ->
                    if (err is HttpException && err.code() == 403) {
                        Observable.just(LoginResult.Failure())
                    } else {
                        Observable.error(err)
                    }
                }
    }

    private fun handleLoginResult(response: LoginResult) {
        when (response) {
            is LoginResult.Success -> {
                SyncJob.scheduleNextSync()
                Track.loginSuccessful()

                // signal success
                setResult(Activity.RESULT_OK)
                finish()
            }

            is LoginResult.Banned -> {
                val date = response.ban.endTime?.let { date ->
                    DurationFormat.timeToPointInTime(this, date)
                }

                val reason = response.ban.reason
                val message = if (date == null) {
                    getString(R.string.banned_forever, reason)
                } else {
                    getString(R.string.banned, date, reason)
                }

                showErrorString(supportFragmentManager, message)
            }

            is LoginResult.Failure -> {
                Track.loginFailed()

                val msg = getString(R.string.login_not_successful)
                showErrorString(supportFragmentManager, msg)
                enableView(true)
            }
        }
    }

    private fun onRegisterClicked() {
        Track.registerLinkClicked()

        val uri = Uri.parse("https://pr0gramm.com/pr0mium/iap?iap=true")
        BrowserHelper.openCustomTab(this, uri)
    }

    private fun onPasswordRecoveryClicked() {
        val intent = Intent(this, RequestPasswordRecoveryActivity::class.java)
        startActivity(intent)
    }

    class DoIfAuthorizedHelper(private val fragment: androidx.fragment.app.Fragment) {
        private var retry: Runnable? = null

        fun onActivityResult(requestCode: Int, resultCode: Int) {
            if (requestCode == RequestCodes.AUTHORIZED_HELPER) {
                if (resultCode == Activity.RESULT_OK) {
                    retry?.run()
                }

                retry = null
            }
        }

        /**
         * Executes the given runnable if a user is signed in. If not, this method shows
         * the login screen. After a successful login, the given 'retry' runnable will be called.
         */
        fun run(runnable: Runnable, retry: Runnable? = null): Boolean {
            val context = fragment.context ?: return false

            val userService: UserService = context.directKodein.instance()
            return if (userService.isAuthorized) {
                runnable.run()
                true

            } else {
                this.retry = retry

                val intent = Intent(context, LoginActivity::class.java)
                startActivityForResult(intent, RequestCodes.AUTHORIZED_HELPER)
                false
            }
        }

        private fun startActivityForResult(intent: Intent, requestCode: Int) {
            fragment.startActivityForResult(intent, requestCode)
        }
    }

    private sealed class LoginResult {
        class Success : LoginResult()
        class Banned(val ban: Api.Login.BanInfo) : LoginResult()
        class Failure : LoginResult()
    }

    companion object {
        private const val PREF_USERNAME = "LoginDialogFragment.username"

        /**
         * Executes the given runnable if a user is signed in. If not, this method
         * will show a login screen.
         */
        fun helper(fragment: androidx.fragment.app.Fragment) = DoIfAuthorizedHelper(fragment)
    }
}
