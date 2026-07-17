package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar
    open val showBackNavigation = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        if (showBackNavigation) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationContentDescription(R.string.navigate_back)
            toolbar.setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
