package com.idn.kmed.cervexa.gallery

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.idn.kmed.cervexa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnPrevPage: MaterialButton
    private lateinit var btnNextPage: MaterialButton
    private lateinit var btnCloseViewer: MaterialButton
    private lateinit var pdfViewPager: ViewPager2
    private lateinit var pbLoading: ProgressBar

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val pageBitmaps = mutableListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        toolbar = findViewById(R.id.toolbar)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnCloseViewer = findViewById(R.id.btnCloseViewer)
        pdfViewPager = findViewById(R.id.pdfViewPager)
        pbLoading = findViewById(R.id.pbLoading)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        btnCloseViewer.setOnClickListener { finish() }

        val filePath = intent.getStringExtra("pdf_path")
        val titleExtra = intent.getStringExtra("pdf_title")

        if (!titleExtra.isNullOrBlank()) {
            toolbar.title = titleExtra
        }

        if (filePath.isNullOrBlank()) {
            Toast.makeText(this, "Path berkas PDF tidak valid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Berkas PDF tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (titleExtra.isNullOrBlank()) {
            toolbar.title = file.name
        }

        btnPrevPage.setOnClickListener {
            val curr = pdfViewPager.currentItem
            if (curr > 0) pdfViewPager.setCurrentItem(curr - 1, true)
        }

        btnNextPage.setOnClickListener {
            val curr = pdfViewPager.currentItem
            if (curr < pageBitmaps.size - 1) pdfViewPager.setCurrentItem(curr + 1, true)
        }

        pdfViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })

        loadAndRenderPdf(file)
    }

    private fun loadAndRenderPdf(file: File) {
        pbLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor?.let { pfd ->
                    pdfRenderer = PdfRenderer(pfd)
                    val count = pdfRenderer?.pageCount ?: 0

                    // Target rendering width 1200px untuk hasil tajam di Smart TV dan HP
                    val targetW = 1200

                    for (i in 0 until count) {
                        pdfRenderer?.openPage(i)?.use { page ->
                            val scale = targetW.toFloat() / page.width.toFloat()
                            val targetH = (page.height * scale).toInt()

                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmaps.add(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                pbLoading.visibility = View.GONE
                if (bitmaps.isEmpty()) {
                    Toast.makeText(this@PdfViewerActivity, "Gagal memuat halaman PDF", Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }

                pageBitmaps.clear()
                pageBitmaps.addAll(bitmaps)

                pdfViewPager.adapter = PdfPageAdapter(pageBitmaps)
                val total = pageBitmaps.size

                btnPrevPage.visibility = if (total > 1) View.VISIBLE else View.GONE
                btnNextPage.visibility = if (total > 1) View.VISIBLE else View.GONE

                updatePageIndicator(0)
                btnCloseViewer.requestFocus()
            }
        }
    }

    private fun updatePageIndicator(position: Int) {
        val total = pageBitmaps.size
        tvPageIndicator.text = "${position + 1} / $total"
        btnPrevPage.isEnabled = position > 0
        btnNextPage.isEnabled = position < total - 1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val curr = pdfViewPager.currentItem
                if (curr > 0) {
                    pdfViewPager.setCurrentItem(curr - 1, true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val curr = pdfViewPager.currentItem
                if (curr < pageBitmaps.size - 1) {
                    pdfViewPager.setCurrentItem(curr + 1, true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            pdfRenderer?.close()
            fileDescriptor?.close()
            pageBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            pageBitmaps.clear()
        }
    }

    private class PdfPageAdapter(private val pages: List<Bitmap>) :
        RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivPage: ImageView = itemView.findViewById(R.id.ivPdfPage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(v)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.ivPage.setImageBitmap(pages[position])
        }

        override fun getItemCount(): Int = pages.size
    }
}
