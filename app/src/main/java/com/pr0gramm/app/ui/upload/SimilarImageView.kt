package com.pr0gramm.app.ui.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import com.pr0gramm.app.R
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.services.UriHelper
import com.pr0gramm.app.ui.dialogs.PopupPlayerFactory
import com.pr0gramm.app.ui.views.KodeinViewMixin
import com.pr0gramm.app.ui.views.SimpleAdapter
import com.pr0gramm.app.ui.views.recyclerViewAdapter
import com.pr0gramm.app.ui.views.viewer.MediaUri
import com.pr0gramm.app.ui.views.viewer.MediaView.Config
import com.pr0gramm.app.util.AndroidUtility
import com.pr0gramm.app.util.observeChange
import com.squareup.picasso.Picasso
import org.kodein.di.erased.instance

/**
 */
class SimilarImageView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : androidx.recyclerview.widget.RecyclerView(context, attrs, defStyleAttr), KodeinViewMixin {

    private val picasso: Picasso by instance()

    init {
        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
    }

    var items: List<Api.Posted.SimilarItem> by observeChange(listOf()) {
        adapter = itemAdapter(items)
    }

    private fun itemAdapter(items: List<Api.Posted.SimilarItem>): SimpleAdapter<Api.Posted.SimilarItem> {
        return recyclerViewAdapter(items) {
            handle<Api.Posted.SimilarItem>() with layout(R.layout.thumbnail) {
                val imageView = it as ImageView

                bind { item ->
                    val imageUri = UriHelper.of(context).thumbnail(item)
                    picasso.load(imageUri)
                            .config(Bitmap.Config.RGB_565)
                            .placeholder(ColorDrawable(0xff333333.toInt()))
                            .into(imageView)

                    imageView.setOnClickListener { _ ->
                        handleItemClicked(item)
                    }
                }
            }
        }
    }

    private fun handleItemClicked(item: Api.Posted.SimilarItem) {
        val activity = AndroidUtility.activityFromContext(context)!!

        val uri = UriHelper.of(context).media(item.image)
        val mediaUri = MediaUri.of(item.id, uri)
        val config = Config(activity, mediaUri)
        PopupPlayerFactory.newInstance(config).show()
    }
}
