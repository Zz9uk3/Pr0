package com.pr0gramm.app.ui.upload

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pr0gramm.app.R
import com.pr0gramm.app.ui.MenuSheetView
import com.pr0gramm.app.util.ignoreException

class UploadTypeDialogFragment : BottomSheetDialogFragment() {
    override fun getTheme(): Int = R.style.MyBottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()

        val menuSheetView = MenuSheetView(context, R.string.hint_upload) { item ->
            dialog?.dismiss()

            if (item.itemId == R.id.action_upload_image) {
                UploadActivity.openForType(context, UploadActivity.MEDIA_TYPE_IMAGE)
            }

            if (item.itemId == R.id.action_upload_video) {
                UploadActivity.openForType(context, UploadActivity.MEDIA_TYPE_VIDEO)
            }
        }

        menuSheetView.inflateMenu(R.menu.menu_upload)

        return menuSheetView
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setOnShowListener {
                val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (bottomSheet is FrameLayout) {
                    ignoreException {
                        BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED)
                    }
                }
            }
        }
    }
}