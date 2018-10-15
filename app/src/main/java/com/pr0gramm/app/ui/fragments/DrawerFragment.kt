package com.pr0gramm.app.ui.fragments

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.pr0gramm.app.R
import com.pr0gramm.app.RequestCodes
import com.pr0gramm.app.UserClasses
import com.pr0gramm.app.api.categories.ExtraCategories
import com.pr0gramm.app.feed.FeedFilter
import com.pr0gramm.app.orm.Bookmark
import com.pr0gramm.app.services.*
import com.pr0gramm.app.services.NavigationProvider.NavigationItem
import com.pr0gramm.app.services.config.ConfigService
import com.pr0gramm.app.ui.*
import com.pr0gramm.app.ui.base.BaseFragment
import com.pr0gramm.app.ui.dialogs.LogoutDialogFragment
import com.pr0gramm.app.util.*
import com.pr0gramm.app.util.AndroidUtility.checkMainThread
import com.pr0gramm.app.util.AndroidUtility.getStatusBarHeight
import com.squareup.picasso.Picasso
import org.kodein.di.direct
import org.kodein.di.erased.instance
import java.util.*

/**
 */
class DrawerFragment : BaseFragment("DrawerFragment") {
    private val userService: UserService by instance()
    private val bookmarkService: BookmarkService by instance()

    private val navigationProvider: NavigationProvider by lazy {
        val k = kodein.direct
        val picasso = k.instance<Picasso>()
        val inboxService = k.instance<InboxService>()
        val configService = k.instance<ConfigService>()
        val extraCategories = k.instance<ExtraCategories>()
        NavigationProvider(requireActivity(), userService, inboxService,
                bookmarkService, configService, extraCategories, picasso)
    }

    private val usernameView: TextView by bindView(R.id.username)
    private val userTypeView: TextView by bindView(R.id.user_type)
    private val benisView: TextView by bindView(R.id.kpi_benis)
    private val benisDeltaView: TextView by bindView(R.id.benis_delta)
    private val benisContainer: View by bindView(R.id.benis_container)
    private val benisGraph: ImageView by bindView(R.id.benis_graph)
    private val actionFAQ: TextView by bindView(R.id.action_faq)
    private val actionPremium: TextView by bindView(R.id.action_premium)
    private val loginView: TextView by bindView(R.id.action_login)
    private val logoutView: TextView by bindView(R.id.action_logout)
    private val feedbackView: TextView by bindView(R.id.action_contact)
    private val settingsView: TextView by bindView(R.id.action_settings)
    private val inviteView: TextView by bindView(R.id.action_invite)
    private val userImageView: View by bindView(R.id.user_image)

    private val navItemsRecyclerView: androidx.recyclerview.widget.RecyclerView by bindView(R.id.drawer_nav_list)

    private val navigationAdapter = NavigationAdapter()
    private val doIfAuthorizedHelper = LoginActivity.helper(this)

    private lateinit var defaultColor: ColorStateList
    private lateinit var markedColor: ColorStateList


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.left_drawer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.context.obtainStyledAttributes(intArrayOf(R.attr.colorAccent, android.R.attr.textColorPrimary)).use { result ->
            markedColor = ColorStateList.valueOf(result.getColor(result.getIndex(0), 0))
            defaultColor = ColorStateList.valueOf(result.getColor(result.getIndex(1), 0))
        }

        // add some space on the top for the translucent status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val params = userImageView.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin += getStatusBarHeight(requireActivity())
        }

        // initialize the top navigation items
        navItemsRecyclerView.adapter = navigationAdapter
        navItemsRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
        navItemsRecyclerView.isNestedScrollingEnabled = false

        // add the static items to the navigation
        navigationAdapter.setNavigationItems(navigationProvider.categoryNavigationItems(null, false))

        settingsView.setOnClickListener {
            val intent = Intent(activity, SettingsActivity::class.java)
            startActivity(intent)
        }

        inviteView.setOnClickListener {
            val intent = Intent(activity, InviteActivity::class.java)
            startActivity(intent)
        }

        actionFAQ.setOnClickListener {
            Track.registerFAQClicked()
            BrowserHelper.openCustomTab(requireActivity(),
                    Uri.parse("https://pr0gramm.com/faq:all?iap=true"),
                    handover = false)
        }

        actionPremium.setOnClickListener {
            Track.registerLinkClicked()
            val uri = Uri.parse("https://pr0gramm.com/pr0mium/iap?iap=true")
            BrowserHelper.openCustomTab(requireActivity(), uri)
        }

        loginView.setOnClickListener {
            val intent = Intent(activity, LoginActivity::class.java)
            startActivity(intent)
        }

        logoutView.setOnClickListener {
            LogoutDialogFragment().show(fragmentManager, null)
        }

        feedbackView.setOnClickListener {
            val intent = Intent(activity, ContactActivity::class.java)
            activity?.startActivityForResult(intent, RequestCodes.FEEDBACK)
        }

        benisGraph.setOnClickListener {
            this.onBenisGraphClicked()
        }

        // colorize all the secondary icons.
        val views = listOf(loginView, logoutView, feedbackView, settingsView, inviteView, actionFAQ, actionPremium)
        for (v in views) {
            val secondary = ColorStateList.valueOf(0x80808080.toInt())
            changeCompoundDrawableColor(v, secondary)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        doIfAuthorizedHelper.onActivityResult(requestCode, resultCode)
    }

    private fun onBenisGraphClicked() {
        startActivity(Intent(context, StatisticsActivity::class.java))

        // close the drawer
        callback.onOtherNavigationItemClicked()
    }

    override fun onResume() {
        super.onResume()

        userService.loginStates
                .observeOnMainThread(firstIsSync = true)
                .bindToLifecycle()
                .subscribe { onLoginStateChanged(it) }

        navigationProvider.navigationItems
                .debug("navigation items", logger)
                .bindToLifecycleAsync()
                .subscribe { navigationAdapter.setNavigationItems(it) }
    }

    private fun onLoginStateChanged(state: UserService.LoginState) {
        if (state.authorized) {
            applyAuthorizedUserState(state)
        } else {
            applyNotAuthorizedState()
        }
    }

    private fun applyAuthorizedUserState(state: UserService.LoginState) {
        usernameView.text = state.name
        usernameView.setOnClickListener { callback.onUsernameClicked() }

        userTypeView.visible = true
        userTypeView.setTextColor(ContextCompat.getColor(context,
                UserClasses.MarkColors[state.mark]))

        userTypeView.text = getString(UserClasses.MarkStrings[state.mark]).toUpperCase()
        userTypeView.setOnClickListener { callback.onUsernameClicked() }


        benisView.text = (state.score).toString()

        val benis = state.benisHistory
        if (benis != null && benis.points.size > 2) {
            benisGraph.setImageDrawable(GraphDrawable(benis))
            benisContainer.visible = true

            updateBenisDeltaForGraph(benis)
        } else {
            updateBenisDelta(0)
        }

        loginView.visible = false
        logoutView.visible = true
        inviteView.visible = true
        actionPremium.visible = !state.premium
    }

    private fun applyNotAuthorizedState() {
        usernameView.setText(R.string.pr0gramm)
        usernameView.setOnClickListener(null)

        userTypeView.text = ""
        userTypeView.visible = false

        benisContainer.visible = false
        benisGraph.setImageDrawable(null)

        loginView.visible = true
        logoutView.visible = false
        inviteView.visible = false
        actionPremium.visible = false
    }

    private fun updateBenisDeltaForGraph(benis: Graph) {
        val delta = (benis.last.y - benis.first.y).toInt()
        updateBenisDelta(delta)
    }

    private fun updateBenisDelta(delta: Int) {
        benisDeltaView.visible = true
        benisDeltaView.setTextColor(if (delta < 0)
            ContextCompat.getColor(context, R.color.benis_delta_negative)
        else
            ContextCompat.getColor(context, R.color.benis_delta_positive))

        benisDeltaView.text = String.format("%s%d", if (delta < 0) "↓" else "↑", delta)
    }

    fun updateCurrentFilters(current: FeedFilter?) {
        navigationAdapter.setCurrentFilter(current)
    }

    interface OnFeedFilterSelected {
        /**
         * Called if a drawer filter was clicked.
         * @param filter The feed filter that was clicked.
         */
        fun onFeedFilterSelectedInNavigation(filter: FeedFilter, startAt: CommentRef? = null)

        /*
         * Called if the user name itself was clicked.
         */
        fun onUsernameClicked()

        /**
         * Some other menu item was clicked and we request that this
         * drawer gets closed
         */
        fun onOtherNavigationItemClicked()

        /**
         * Navigate to the favorites of the given user
         */
        fun onNavigateToFavorites(username: String)
    }

    private val callback: OnFeedFilterSelected get() {
        return activity as OnFeedFilterSelected
    }

    private inner class NavigationAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<NavigationItemViewHolder>() {
        private val allItems = ArrayList<NavigationItem>()
        private var currentFilter: FeedFilter? = null
        private var selected: NavigationItem? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NavigationItemViewHolder {
            val inflater = LayoutInflater.from(parent.context).cloneInContext(parent.context)
            val view = inflater.inflate(viewType, parent, false)
            return NavigationItemViewHolder(view)
        }

        override fun getItemViewType(position: Int): Int {
            return allItems[position].layout
        }

        override fun onBindViewHolder(holder: NavigationItemViewHolder, position: Int) {
            val item = allItems[position]
            holder.text.text = item.title

            // set the icon of the image
            holder.text.setCompoundDrawablesWithIntrinsicBounds(item.icon, null, null, null)

            // update color
            val color = if ((selected == item)) markedColor else defaultColor
            holder.text.setTextColor(color)
            changeCompoundDrawableColor(holder.text, color.withAlpha(127))

            // handle clicks
            holder.itemView.setOnClickListener {
                dispatchItemClick(item)
            }

            holder.itemView.setOnLongClickListener { _ ->
                item.bookmark?.let { showDialogToRemoveBookmark(it) }
                true
            }

            if (item.action === NavigationProvider.ActionType.MESSAGES) {
                holder.unread?.text = (item.unreadCount).toString()
                holder.unread?.visibility = if (item.unreadCount > 0) View.VISIBLE else View.GONE
            }
        }

        override fun getItemCount(): Int {
            return allItems.size
        }

        fun setNavigationItems(items: List<NavigationItem>) {
            checkMainThread()
            this.allItems.clear()
            this.allItems.addAll(items)
            merge()
        }

        fun setCurrentFilter(current: FeedFilter?) {
            currentFilter = current
            merge()
        }

        private fun merge() {
            checkMainThread()
            logger.debug("Merging items now")

            selected = allItems.firstOrNull { it.hasFilter && it.filter == currentFilter }

            if (selected == null) {
                currentFilter?.let { current ->
                    selected = allItems.firstOrNull { it.filter?.feedType === current.feedType }
                }
            }

            notifyDataSetChanged()
        }
    }

    private fun dispatchItemClick(item: NavigationItem) {
        when (item.action) {
            NavigationProvider.ActionType.FILTER,
            NavigationProvider.ActionType.BOOKMARK ->
                callback.onFeedFilterSelectedInNavigation(item.filter!!)

            NavigationProvider.ActionType.UPLOAD -> {
                showUploadActivity()
                callback.onOtherNavigationItemClicked()
            }

            NavigationProvider.ActionType.MESSAGES -> {
                showInboxActivity(item.unreadCount)
                callback.onOtherNavigationItemClicked()
            }

            NavigationProvider.ActionType.FAVORITES ->
                callback.onNavigateToFavorites(item.filter!!.likes!!)

            NavigationProvider.ActionType.URI ->
                item.uri?.let { openActionUri(it) }
        }
    }

    private fun openActionUri(uri: Uri) {
        Track.specialMenuActionClicked(uri)

        // check if we can handle the uri in the app
        FilterParser.parse(uri)?.let { parsed ->
            callback.onFeedFilterSelectedInNavigation(parsed.filter, parsed.start)
            return
        }

        BrowserHelper.openCustomTab(context, uri)
    }

    private fun showInboxActivity(unreadCount: Int) {
        showInboxActivity(if (unreadCount == 0) InboxType.ALL else InboxType.UNREAD)
    }

    private fun showInboxActivity(inboxType: InboxType) {
        val run = Runnable {
            val intent = Intent(activity, InboxActivity::class.java)
            intent.putExtra(InboxActivity.EXTRA_INBOX_TYPE, inboxType.ordinal)
            startActivity(intent)
        }

        doIfAuthorizedHelper.run(run, run)
    }

    private fun showUploadActivity() {
        val run = Runnable {
            (activity as MainActionHandler).showUploadBottomSheet()
        }

        doIfAuthorizedHelper.run(run, run)
    }

    private fun showDialogToRemoveBookmark(bookmark: Bookmark) {
        showBottomSheet(this) {
            content(R.string.do_you_want_to_remove_this_bookmark)
            negative(R.string.cancel)
            positive(R.string.delete) { bookmarkService.delete(bookmark) }
        }
    }

    /**
     * Fakes the drawable tint by applying a color filter on all compound
     * drawables of this view.

     * @param view  The view to "tint"
     * @param color The color with which the drawables are to be tinted.
     */
    private fun changeCompoundDrawableColor(view: TextView, color: ColorStateList) {
        val defaultColor = color.defaultColor
        val drawables = view.compoundDrawables

        drawables.filterNotNull().forEach {
            // fake the tint with a color filter.
            it.mutate().setColorFilter(defaultColor, PorterDuff.Mode.SRC_IN)
        }
    }

    private class NavigationItemViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        val text: TextView = (itemView as? TextView ?: itemView.find(R.id.title))
        val unread: TextView? = itemView.findOptional(R.id.unread_count)
    }
}
