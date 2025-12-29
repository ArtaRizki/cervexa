package com.idn.kmed.cervexa.media

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.gallery.MediaPagerActivity
import com.google.android.material.appbar.MaterialToolbar
import java.util.ArrayList

class MediaListActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var rv: RecyclerView
    private lateinit var progress: View

    private lateinit var repo: MediaRepository
    private lateinit var adapter: SessionListAdapter

    private var loading = false
    private var loaded = 0
    private val pageSize = 40

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_list)

        toolbar = findViewById(R.id.toolbar)
        rv = findViewById(R.id.rv)
        progress = findViewById(R.id.progress)

        repo = MediaRepository(this)
        adapter = SessionListAdapter(
            onSessionClick = { session ->
                val all = repo.listMediaInSession(session)
                val paths = ArrayList(all.map { it.file.absolutePath })
                val types = ArrayList(all.map { it.type.name })
                val idx = all.indexOfFirst { it.file == session.thumb.file }.coerceAtLeast(0)

                startActivity(Intent(this, MediaPagerActivity::class.java).apply {
                    putStringArrayListExtra("paths", paths)
                    putStringArrayListExtra("types", types)
                    putExtra("index", idx)
                })
            },
            onMoreClick = {
            }
        )

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        rv.addItemDecoration(
            StickyMonthHeaderDecoration(
                provider = adapter,                // karena adapter implements StickyHeaderProvider
                headerLayoutRes = R.layout.item_month_header
            )
        )

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
                if (!loading && last >= adapter.itemCount - 10) loadNext()
            }
        })

        loadNext()
    }

    private fun loadNext() {
        loading = true
        progress.visibility = View.VISIBLE
        rv.post {
            val batch = repo.loadPage(loaded, pageSize)
            adapter.append(batch)
            loaded += batch.size
            progress.visibility = View.GONE
            loading = false
        }
    }
}