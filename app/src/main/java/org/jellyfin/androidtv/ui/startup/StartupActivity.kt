package org.jellyfin.androidtv.ui.startup

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.JellyfinApplication
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.SessionRepositoryState
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.databinding.ActivityMainBinding
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.startup.fragment.SelectServerFragment
import org.jellyfin.androidtv.ui.startup.fragment.ServerFragment
import org.jellyfin.androidtv.ui.startup.fragment.SplashFragment
import org.jellyfin.androidtv.ui.startup.fragment.StartupToolbarFragment
import org.jellyfin.androidtv.util.applyTheme
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.util.UUID

/**
 * 这是app的启动页，
 * 用于检测网络权限，检测是否已登录，检测是否已选择服务器，检测是否已选择用户，
 * 检测是否已选择媒体库，检测是否已选择播放器，检测是否已选择播放列表，检测是否已选择播放器，
 * 检测是否已选择播放列表，检测是否已选择播放器，检测是否已选择播放列表，检测是否
 */
class StartupActivity : FragmentActivity() {
	companion object {
		const val EXTRA_ITEM_ID = "ItemId"
		const val EXTRA_ITEM_IS_USER_VIEW = "ItemIsUserView"
		const val EXTRA_HIDE_SPLASH = "HideSplash"
	}

	private val startupViewModel: StartupViewModel by viewModel()
	private val api: ApiClient by inject()
	private val mediaManager: MediaManager by inject()
	private val sessionRepository: SessionRepository by inject()
	private val userRepository: UserRepository by inject()
	private val navigationRepository: NavigationRepository by inject()

	private lateinit var binding: ActivityMainBinding

	/**
	 * 对传入的权限数组进行检查，如果有任何一个权限被拒绝，则退出应用。
	 */
	private val networkPermissionsRequester = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { grants ->
		val anyRejected = grants.any { !it.value }

		if (anyRejected) {
			//权限检查不通过，退出应用
			// Permission denied, exit the app.
			Toast.makeText(this, R.string.no_network_permissions, Toast.LENGTH_LONG).show()
			finish()
		} else {
			//权限检查通过，继续执行
			onPermissionsGranted()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		binding = ActivityMainBinding.inflate(layoutInflater)
		binding.background.setContent { AppBackground() }
		binding.screensaver.isVisible = false
		setContentView(binding.root)

		if (!intent.getBooleanExtra(EXTRA_HIDE_SPLASH, false)) showSplash()

		// Ensure basic permissions
		/**
		 * 检查权限
		 * 用于允许应用程序访问互联网
		 * 用于允许应用程序访问网络状态信息，例如网络连接状态和数据使用情况。
		 */
		networkPermissionsRequester.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE))
	}

	override fun onResume() {
		super.onResume()

		applyTheme()
	}

	/**
	 * 在权限检查通过后，检查是否已登录，如果未登录，则显示登录界面。
	 */
	private fun onPermissionsGranted() = sessionRepository.state
		.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
		.filter { it == SessionRepositoryState.READY }
		.onEach {
			val session = sessionRepository.currentSession.value
			//session!= null说明已经登录
			if (session != null) {
				Timber.i("Found a session in the session repository, waiting for the currentUser in the application class.")

				showSplash()

				val currentUser = userRepository.currentUser.first { it != null }
				Timber.i("CurrentUser changed to ${currentUser?.id} while waiting for startup.")

				lifecycleScope.launch {
					openNextActivity()
				}
			} else {
				//没有session，显示登录界面
				// Clear audio queue in case left over from last run
				mediaManager.clearAudioQueue()

				val server = startupViewModel.getLastServer()
				if (server != null) showServer(server.id)
				else showServerSelection()
			}
		}.launchIn(lifecycleScope)

	private suspend fun openNextActivity() {
		val itemId = when {
			intent.action == Intent.ACTION_VIEW && intent.data != null -> intent.data.toString()
			else -> intent.getStringExtra(EXTRA_ITEM_ID)
		}?.toUUIDOrNull()
		val itemIsUserView = intent.getBooleanExtra(EXTRA_ITEM_IS_USER_VIEW, false)

		Timber.d("Determining next activity (action=${intent.action}, itemId=$itemId, itemIsUserView=$itemIsUserView)")

		// Start session
		(application as? JellyfinApplication)?.onSessionStart()

		// Create destination
		val destination = when {
			// Search is requested
			intent.action === Intent.ACTION_SEARCH -> Destinations.search
			// User view item is requested
			itemId != null && itemIsUserView -> {
				val item by api.userLibraryApi.getItem(itemId = itemId)
				ItemLauncher.getUserViewDestination(item)
			}
			// Other item is requested
			itemId != null -> Destinations.itemDetails(itemId)
			// No destination requested, use default
			else -> null
		}

		navigationRepository.reset(destination)

		val intent = Intent(this, MainActivity::class.java)
		// Clear navigation history
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
		Timber.d("Opening next activity $intent")
		startActivity(intent)
		finishAfterTransition()
	}

	// Fragment switching
	private fun showSplash() {
		// Prevent progress bar flashing
		if (supportFragmentManager.findFragmentById(R.id.content_view) is SplashFragment) return

		supportFragmentManager.commit {
			replace<SplashFragment>(R.id.content_view)
		}
	}

	private fun showServer(id: UUID) = supportFragmentManager.commit {
		replace<StartupToolbarFragment>(R.id.content_view)
		add<ServerFragment>(R.id.content_view, null, bundleOf(
			ServerFragment.ARG_SERVER_ID to id.toString()
		))
	}

	private fun showServerSelection() = supportFragmentManager.commit {
		replace<StartupToolbarFragment>(R.id.content_view)
		add<SelectServerFragment>(R.id.content_view)
	}
}
