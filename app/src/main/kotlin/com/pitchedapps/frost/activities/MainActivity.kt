package com.pitchedapps.frost.activities

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.design.widget.AppBarLayout
import android.support.design.widget.CoordinatorLayout
import android.support.design.widget.FloatingActionButton
import android.support.design.widget.TabLayout
import android.support.v4.app.ActivityOptionsCompat
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import ca.allanwang.kau.searchview.SearchItem
import ca.allanwang.kau.searchview.SearchView
import ca.allanwang.kau.searchview.SearchViewHolder
import ca.allanwang.kau.searchview.bindSearchView
import ca.allanwang.kau.utils.*
import co.zsmb.materialdrawerkt.builders.Builder
import co.zsmb.materialdrawerkt.builders.accountHeader
import co.zsmb.materialdrawerkt.builders.drawer
import co.zsmb.materialdrawerkt.draweritems.badgeable.primaryItem
import co.zsmb.materialdrawerkt.draweritems.badgeable.secondaryItem
import co.zsmb.materialdrawerkt.draweritems.divider
import co.zsmb.materialdrawerkt.draweritems.profile.profile
import co.zsmb.materialdrawerkt.draweritems.profile.profileSetting
import com.crashlytics.android.answers.ContentViewEvent
import com.mikepenz.google_material_typeface_library.GoogleMaterial
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.materialdrawer.AccountHeader
import com.mikepenz.materialdrawer.Drawer
import com.pitchedapps.frost.BuildConfig
import com.pitchedapps.frost.R
import com.pitchedapps.frost.contracts.ActivityWebContract
import com.pitchedapps.frost.contracts.FileChooserContract
import com.pitchedapps.frost.contracts.FileChooserDelegate
import com.pitchedapps.frost.contracts.VideoViewHolder
import com.pitchedapps.frost.dbflow.TAB_COUNT
import com.pitchedapps.frost.dbflow.loadFbCookie
import com.pitchedapps.frost.dbflow.loadFbTabs
import com.pitchedapps.frost.enums.MainActivityLayout
import com.pitchedapps.frost.enums.Theme
import com.pitchedapps.frost.facebook.FbCookie
import com.pitchedapps.frost.facebook.FbCookie.switchUser
import com.pitchedapps.frost.facebook.FbItem
import com.pitchedapps.frost.facebook.PROFILE_PICTURE_URL
import com.pitchedapps.frost.fragments.WebFragment
import com.pitchedapps.frost.parsers.SearchParser
import com.pitchedapps.frost.utils.*
import com.pitchedapps.frost.utils.iab.FrostBilling
import com.pitchedapps.frost.utils.iab.IS_FROST_PRO
import com.pitchedapps.frost.utils.iab.IabMain
import com.pitchedapps.frost.views.BadgedIcon
import com.pitchedapps.frost.views.FrostVideoViewer
import com.pitchedapps.frost.views.FrostViewPager
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(),
        ActivityWebContract, FileChooserContract by FileChooserDelegate(),
        VideoViewHolder, SearchViewHolder,
        FrostBilling by IabMain() {

    lateinit var adapter: SectionsPagerAdapter
    override val frameWrapper: FrameLayout by bindView(R.id.frame_wrapper)
    val toolbar: Toolbar by bindView(R.id.toolbar)
    val viewPager: FrostViewPager by bindView(R.id.container)
    val fab: FloatingActionButton by bindView(R.id.fab)
    val tabs: TabLayout by bindView(R.id.tabs)
    val appBar: AppBarLayout by bindView(R.id.appbar)
    val coordinator: CoordinatorLayout by bindView(R.id.main_content)
    override var videoViewer: FrostVideoViewer? = null
    lateinit var drawer: Drawer
    lateinit var drawerHeader: AccountHeader
    var webFragmentObservable = PublishSubject.create<Int>()!!
    var lastPosition = -1
    val headerBadgeObservable = PublishSubject.create<String>()
    var firstLoadFinished = false
        set(value) {
            if (field && value) return //both vals are already true
            L.i("First fragment load has finished")
            field = value
        }
    override var searchView: SearchView? = null
    private val searchViewCache = mutableMapOf<String, List<SearchItem>>()

    companion object {
        const val ACTIVITY_SETTINGS = 97
        /*
         * Possible responses from the SettingsActivity
         * after the configurations have changed
         */
        const val REQUEST_RESTART_APPLICATION = 1 shl 1
        const val REQUEST_RESTART = 1 shl 2
        const val REQUEST_REFRESH = 1 shl 3
        const val REQUEST_WEB_ZOOM = 1 shl 4
        const val REQUEST_NAV = 1 shl 5
        const val REQUEST_SEARCH = 1 shl 6
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.VERSION_CODE > Prefs.versionCode) {
            Prefs.versionCode = BuildConfig.VERSION_CODE
            if (!BuildConfig.DEBUG) {
                frostChangelog()
                frostAnswersCustom("Version",
                        "Version code" to BuildConfig.VERSION_CODE,
                        "Version name" to BuildConfig.VERSION_NAME,
                        "Build type" to BuildConfig.BUILD_TYPE,
                        "Frost id" to Prefs.frostId)
            }
        }
        setFrameContentView(Prefs.mainActivityLayout.layoutRes)
        setSupportActionBar(toolbar)
        adapter = SectionsPagerAdapter(supportFragmentManager, loadFbTabs())
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = TAB_COUNT
        viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (lastPosition == position) return
                if (lastPosition != -1) webFragmentObservable.onNext(-(lastPosition + 1))
                webFragmentObservable.onNext(position)
                lastPosition = position
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                val delta = positionOffset * (255 - 128).toFloat()
                tabsForEachView { tabPosition, view ->
                    view.setAllAlpha(when (tabPosition) {
                        position -> 255.0f - delta
                        position + 1 -> 128.0f + delta
                        else -> 128f
                    })
                }
            }
        })
        viewPager.post { webFragmentObservable.onNext(0); lastPosition = 0 } //trigger hook so title is set
        setupDrawer(savedInstanceState)
        setupTabs()
//        fab.setOnClickListener { view ->
//            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
//                    .setAction("Action", null).show()
//        }
        setFrostColors(toolbar, themeWindow = false, headers = arrayOf(appBar), backgrounds = arrayOf(viewPager))
        tabs.setBackgroundColor(Prefs.mainActivityLayout.backgroundColor())
        onCreateBilling()
    }

    fun tabsForEachView(action: (position: Int, view: BadgedIcon) -> Unit) {
        (0 until tabs.tabCount).asSequence().forEach { i ->
            action(i, tabs.getTabAt(i)!!.customView as BadgedIcon)
        }
    }

    fun setupTabs() {
        viewPager.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(tabs))
        tabs.addOnTabSelectedListener(object : TabLayout.ViewPagerOnTabSelectedListener(viewPager) {
            override fun onTabReselected(tab: TabLayout.Tab) {
                super.onTabReselected(tab)
                currentFragment.web.scrollOrRefresh()
            }

            override fun onTabSelected(tab: TabLayout.Tab) {
                super.onTabSelected(tab)
                (tab.customView as BadgedIcon).badgeText = null
            }
        })
        headerBadgeObservable.throttleFirst(15, TimeUnit.SECONDS).subscribeOn(Schedulers.newThread())
                .map { Jsoup.parse(it) }
                .filter { it.select("[data-sigil=count]").size >= 0 } //ensure headers exist
                .map {
                    val feed = it.select("[data-sigil*=feed] [data-sigil=count]")
                    val requests = it.select("[data-sigil*=requests] [data-sigil=count]")
                    val messages = it.select("[data-sigil*=messages] [data-sigil=count]")
                    val notifications = it.select("[data-sigil*=notifications] [data-sigil=count]")
                    return@map arrayOf(feed, requests, messages, notifications).map { it?.getOrNull(0)?.ownText() }
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { (feed, requests, messages, notifications) ->
                    tabsForEachView { _, view ->
                        when (view.iicon) {
                            FbItem.FEED.icon -> view.badgeText = feed
                            FbItem.FRIENDS.icon -> view.badgeText = requests
                            FbItem.MESSAGES.icon -> view.badgeText = messages
                            FbItem.NOTIFICATIONS.icon -> view.badgeText = notifications
                        }
                    }
                }
        adapter.pages.forEach {
            tabs.addTab(tabs.newTab()
                    .setCustomView(BadgedIcon(this).apply { iicon = it.icon }))
        }
    }

    fun setupDrawer(savedInstanceState: Bundle?) {
        val navBg = Prefs.bgColor.withMinAlpha(200).toLong()
        val navHeader = Prefs.headerColor.withMinAlpha(200)
        drawer = drawer {
            toolbar = this@MainActivity.toolbar
            savedInstance = savedInstanceState
            translucentStatusBar = false
            sliderBackgroundColor = navBg
            drawerHeader = accountHeader {
                customViewRes = R.layout.material_drawer_header
                textColor = Prefs.iconColor.toLong()
                backgroundDrawable = ColorDrawable(navHeader)
                selectionSecondLineShown = false
                cookies().forEach { (id, name) ->
                    profile(name = name ?: "") {
                        iconUrl = PROFILE_PICTURE_URL(id)
                        textColor = Prefs.textColor.toLong()
                        selectedTextColor = Prefs.textColor.toLong()
                        selectedColor = 0x00000001.toLong()
                        identifier = id
                    }
                }
                profileSetting(nameRes = R.string.kau_logout) {
                    iicon = GoogleMaterial.Icon.gmd_exit_to_app
                    iconColor = Prefs.textColor.toLong()
                    textColor = Prefs.textColor.toLong()
                    identifier = -2L
                }
                profileSetting(nameRes = R.string.kau_add_account) {
                    iconDrawable = IconicsDrawable(this@MainActivity, GoogleMaterial.Icon.gmd_add).actionBar().paddingDp(5).color(Prefs.textColor)
                    textColor = Prefs.textColor.toLong()
                    identifier = -3L
                }
                profileSetting(nameRes = R.string.kau_manage_account) {
                    iicon = GoogleMaterial.Icon.gmd_settings
                    iconColor = Prefs.textColor.toLong()
                    textColor = Prefs.textColor.toLong()
                    identifier = -4L
                }
                onProfileChanged { _, profile, current ->
                    if (current) launchWebOverlay(FbItem.PROFILE.url)
                    else when (profile.identifier) {
                        -2L -> {
                            val currentCookie = loadFbCookie(Prefs.userId)
                            if (currentCookie == null) {
                                toast(R.string.account_not_found)
                                FbCookie.reset { launchLogin(cookies(), true) }
                            } else {
                                materialDialogThemed {
                                    title(R.string.kau_logout)
                                    content(String.format(string(R.string.kau_logout_confirm_as_x), currentCookie.name ?: Prefs.userId.toString()))
                                    positiveText(R.string.kau_yes)
                                    negativeText(R.string.kau_no)
                                    onPositive { _, _ -> FbCookie.logout(this@MainActivity) }
                                }
                            }
                        }
                        -3L -> launchNewTask(LoginActivity::class.java, clearStack = false)
                        -4L -> launchNewTask(SelectorActivity::class.java, cookies(), false)
                        else -> {
                            switchUser(profile.identifier, { refreshAll() })
                            tabsForEachView { _, view -> view.badgeText = null }
                        }
                    }
                    false
                }
            }
            drawerHeader.setActiveProfile(Prefs.userId)
            primaryFrostItem(FbItem.FEED_MOST_RECENT)
            primaryFrostItem(FbItem.FEED_TOP_STORIES)
            primaryFrostItem(FbItem.ACTIVITY_LOG)
            divider()
            primaryFrostItem(FbItem.PHOTOS)
            primaryFrostItem(FbItem.GROUPS)
            primaryFrostItem(FbItem.FRIENDS)
            primaryFrostItem(FbItem.CHAT)
            primaryFrostItem(FbItem.PAGES)
            divider()
            primaryFrostItem(FbItem.EVENTS)
            primaryFrostItem(FbItem.BIRTHDAYS)
            primaryFrostItem(FbItem.ON_THIS_DAY)
            divider()
            primaryFrostItem(FbItem.NOTES)
            primaryFrostItem(FbItem.SAVED)
        }
    }

    private fun Builder.primaryFrostItem(item: FbItem) = this.primaryItem(item.titleId) {
        iicon = item.icon
        iconColor = Prefs.textColor.toLong()
        textColor = Prefs.textColor.toLong()
        selectedIconColor = Prefs.textColor.toLong()
        selectedTextColor = Prefs.textColor.toLong()
        selectedColor = 0x00000001.toLong()
        identifier = item.titleId.toLong()
        onClick { _ ->
            frostAnswers {
                logContentView(ContentViewEvent()
                        .putContentName(item.name)
                        .putContentType("drawer_item"))
            }
            launchWebOverlay(item.url)
            false
        }
    }

    private fun Builder.secondaryFrostItem(@StringRes title: Int, onClick: () -> Unit) = this.secondaryItem(title) {
        textColor = Prefs.textColor.toLong()
        selectedIconColor = Prefs.textColor.toLong()
        selectedTextColor = Prefs.textColor.toLong()
        selectedColor = 0x00000001.toLong()
        identifier = title.toLong()
        onClick { _ -> onClick(); false }
    }

    fun refreshAll() {
        webFragmentObservable.onNext(WebFragment.REQUEST_REFRESH)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        toolbar.tint(Prefs.iconColor)
        setMenuIcons(menu, Prefs.iconColor,
                R.id.action_settings to GoogleMaterial.Icon.gmd_settings,
                R.id.action_search to GoogleMaterial.Icon.gmd_search)
        searchViewBindIfNull {
            bindSearchView(menu, R.id.action_search, Prefs.iconColor) {
                textCallback = { query, _ ->
                    val results = searchViewCache[query]
                    if (results != null)
                        runOnUiThread { searchView?.results = results }
                    else
                        doAsync {
                            val data = SearchParser.query(query) ?: return@doAsync
                            val items = data.map { SearchItem(it.href, it.title, it.description) }.toMutableList()
                            if (items.isNotEmpty())
                                items.add(SearchItem("${FbItem._SEARCH.url}?q=$query", string(R.string.show_all_results), iicon = null))
                            searchViewCache.put(query, items)
                            uiThread { searchView?.results = items }
                        }
                }
                textDebounceInterval = 300
                searchCallback = { query, _ -> launchWebOverlay("${FbItem._SEARCH.url}/?q=$query"); true }
                closeListener = { _ -> searchViewCache.clear() }
                foregroundColor = Prefs.textColor
                backgroundColor = Prefs.bgColor.withMinAlpha(200)
                onItemClick = { _, key, _, _ -> launchWebOverlay(key) }
            }
        }
        return true
    }

    @SuppressLint("RestrictedApi")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putParcelableArrayListExtra(EXTRA_COOKIES, cookies())
                val bundle = ActivityOptionsCompat.makeCustomAnimation(this, R.anim.kau_slide_in_right, R.anim.kau_fade_out).toBundle()
                startActivityForResult(intent, ACTIVITY_SETTINGS, bundle)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun openFileChooser(filePathCallback: ValueCallback<Array<Uri>?>, fileChooserParams: WebChromeClient.FileChooserParams) {
        openMediaPicker(filePathCallback, fileChooserParams)
    }

    @SuppressLint("NewApi")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (onActivityResultWeb(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACTIVITY_SETTINGS) {
            if (resultCode and REQUEST_RESTART_APPLICATION > 0) { //completely restart application
                L.d("Restart Application Requested")
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                val pending = PendingIntent.getActivity(this, 666, intent, PendingIntent.FLAG_CANCEL_CURRENT)
                val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (buildIsMarshmallowAndUp)
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 100, pending)
                else
                    alarm.setExact(AlarmManager.RTC, System.currentTimeMillis() + 100, pending)
                finish()
                System.exit(0)
                return
            }
            if (resultCode and REQUEST_RESTART > 0) return restart()
            /*
             * These results can be stacked
             */
            if (resultCode and REQUEST_REFRESH > 0) webFragmentObservable.onNext(WebFragment.REQUEST_REFRESH)
            if (resultCode and REQUEST_NAV > 0) frostNavigationBar()
            if (resultCode and REQUEST_WEB_ZOOM > 0) webFragmentObservable.onNext(WebFragment.REQUEST_TEXT_ZOOM)
            if (resultCode and REQUEST_SEARCH > 0) invalidateOptionsMenu()
        }
    }

    override fun onResume() {
        super.onResume()
        FbCookie.switchBackUser { }
    }

    override fun onStart() {
        //validate some pro features
        if (!IS_FROST_PRO) {
            if (Prefs.theme == Theme.CUSTOM.ordinal) Prefs.theme = Theme.DEFAULT.ordinal
        }
        super.onStart()
    }

    override fun onDestroy() {
        onDestroyBilling()
        super.onDestroy()
    }

    override fun backConsumer(): Boolean {
        if (currentFragment.onBackPressed()) return true
        if (Prefs.exitConfirmation) {
            materialDialogThemed {
                title(R.string.kau_exit)
                content(R.string.kau_exit_confirmation)
                positiveText(R.string.kau_yes)
                negativeText(R.string.kau_no)
                onPositive { _, _ -> finish() }
                checkBoxPromptRes(R.string.kau_do_not_show_again, false, { _, b -> Prefs.exitConfirmation = !b })
            }
            return true
        }
        return false
    }

    inline val currentFragment
        get() = supportFragmentManager.findFragmentByTag("android:switcher:${R.id.container}:${viewPager.currentItem}") as WebFragment

    inner class SectionsPagerAdapter(fm: FragmentManager, val pages: List<FbItem>) : FragmentPagerAdapter(fm) {

        override fun getItem(position: Int): Fragment {
            val fragment = WebFragment(pages[position], position)
            //If first load hasn't occurred, add a listener
            if (!firstLoadFinished) {
                var disposable: Disposable? = null
                fragment.post {
                    disposable = it.web.refreshObservable.subscribe {
                        if (!it) {
                            //Ensure first load finisher only happens once
                            if (!firstLoadFinished) firstLoadFinished = true
                            disposable?.dispose()
                            disposable = null
                        }
                    }
                }
            }
            return fragment
        }

        override fun getCount() = pages.size

        override fun getPageTitle(position: Int): CharSequence = getString(pages[position].titleId)
    }

    override val lowerVideoPadding: PointF
        get() =
            if (Prefs.mainActivityLayout == MainActivityLayout.BOTTOM_BAR)
                PointF(0f, toolbar.height.toFloat())
            else
                PointF(0f, 0f)

}
