package com.idn.kmed.cervexa.media

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R

interface StickyHeaderProvider {
    fun isHeader(position: Int): Boolean
    fun getHeaderText(position: Int): String
}

class StickyMonthHeaderDecoration(
    private val provider: StickyHeaderProvider,
    @LayoutRes private val headerLayoutRes: Int = R.layout.item_month_header // WAJIB ada TextView id: tvMonth
) : RecyclerView.ItemDecoration() {

    private var headerView: View? = null
    private var headerText: TextView? = null

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter ?: return
        if (adapter.itemCount == 0 || parent.childCount == 0) return

        ensureHeaderInflated(parent)

        val topChild = parent.getChildAt(0) ?: return
        val topPos = parent.getChildAdapterPosition(topChild)
        if (topPos == RecyclerView.NO_POSITION) return

        // set text SETELAH view punya LayoutParams
        headerText!!.text = provider.getHeaderText(topPos)

        // ukur & layout header sesuai lebar parent
        measureAndLayout(parent)

        // push-up kalau header berikutnya mendekat
        var offsetY = 0
        val headerHeight = headerView!!.measuredHeight
        for (i in 1 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION && provider.isHeader(pos)) {
                val childTop = child.top
                if (childTop in 1..headerHeight) {
                    offsetY = childTop - headerHeight
                    break
                }
            }
        }

        c.save()
        c.translate(0f, offsetY.toFloat())
        headerView!!.draw(c)
        c.restore()
    }

    private fun ensureHeaderInflated(parent: RecyclerView) {
        if (headerView == null) {
            // inflate dengan parent agar dapat LayoutParams yang valid
            headerView = LayoutInflater.from(parent.context).inflate(headerLayoutRes, parent, false)
            headerText = headerView!!.findViewById(R.id.tvMonth)
            // fallback kalau masih null
            if (headerView!!.layoutParams == null) {
                headerView!!.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
    }

    private fun measureAndLayout(parent: RecyclerView) {
        val lp = headerView!!.layoutParams
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.UNSPECIFIED)

        headerView!!.measure(
            ViewGroup.getChildMeasureSpec(widthSpec, parent.paddingLeft + parent.paddingRight, lp.width),
            ViewGroup.getChildMeasureSpec(heightSpec, parent.paddingTop + parent.paddingBottom, lp.height)
        )
        headerView!!.layout(0, 0, headerView!!.measuredWidth, headerView!!.measuredHeight)
    }
}