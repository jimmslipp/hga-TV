package com.hga.media.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hga.media.R
import com.hga.media.data.Category
import com.hga.media.data.Kind
import com.hga.media.data.Repo
import com.hga.media.util.dp
import com.hga.media.util.toast
import kotlinx.coroutines.launch

/**
 * One screen serves Live TV, Movies, Series and Favourites. Categories on the
 * left, content on the right, search across the top - the layout people already
 * know from every other IPTV app, so staff need no training.
 */
class BrowseActivity : AppCompatActivity() {

    private var kind = Kind.LIVE
    private var favouritesOnly = false
    private var currentCategoryId: String = Repo.ALL
    private var inEpisodeView = false

    private lateinit var title: TextView
    private lateinit var search: EditText
    private lateinit var categoryList: RecyclerView
    private lateinit var contentList: RecyclerView
    private lateinit var empty: TextView
    private lateinit var letterRail: LinearLayout
    private lateinit var letterScroll: ScrollView
    private lateinit var btnCategories: TextView

    /** Names of whatever is currently listed, used by the A-Z jump. */
    private var currentNames: List<String> = emptyList()

    private lateinit var categoryAdapter: CategoryAdapter
    private var channelAdapter: ChannelAdapter? = null
    private var posterAdapter: PosterAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        UiKit.goFullScreen(this)
        if (!UiKit.ensureLoaded(this)) return

        kind = intent.getIntExtra(EXTRA_KIND, Kind.LIVE)
        favouritesOnly = intent.getBooleanExtra(EXTRA_FAVOURITES, false)

        title = findViewById(R.id.browseTitle)
        search = findViewById(R.id.browseSearch)
        categoryList = findViewById(R.id.categoryList)
        contentList = findViewById(R.id.contentList)
        empty = findViewById(R.id.browseEmpty)
        letterRail = findViewById(R.id.letterRail)
        letterScroll = findViewById(R.id.letterScroll)
        btnCategories = findViewById(R.id.btnCategories)
        buildLetterRail()
        btnCategories.setOnClickListener { chooseCategories() }

        title.setText(
            when {
                favouritesOnly -> R.string.menu_favourites
                kind == Kind.VOD -> R.string.menu_movies
                kind == Kind.SERIES -> R.string.menu_series
                else -> R.string.menu_live
            }
        )

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryAdapter = CategoryAdapter(buildCategories()) { category, _ ->
            if (category.id != currentCategoryId) {
                currentCategoryId = category.id
                refreshContent()
            }
        }
        categoryList.adapter = categoryAdapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshContent()
        })

        setUpContentList()
        refreshContent()
        categoryList.requestFocus()
    }

    /**
     * Typing on a TV remote is miserable, so the list gets an A-Z rail down the
     * right instead. Press right from the content list, pick a letter, and the
     * list jumps to the first entry starting with it.
     */
    private fun buildLetterRail() {
        letterRail.removeAllViews()
        val letters = listOf("#") + ('A'..'Z').map { it.toString() }
        for (letter in letters) {
            val tv = TextView(this).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(resources.getColor(R.color.text_secondary))
                setBackgroundResource(R.drawable.bg_letter)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34f)
                ).apply { bottomMargin = dp(2f) }
                setOnClickListener { jumpToLetter(letter) }
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) jumpToLetter(letter) }
            }
            letterRail.addView(tv)
        }
    }

    private fun jumpToLetter(letter: String) {
        if (currentNames.isEmpty()) return
        val index = if (letter == "#") {
            currentNames.indexOfFirst { it.isNotEmpty() && !it[0].isLetter() }
        } else {
            currentNames.indexOfFirst { it.trim().uppercase().startsWith(letter) }
        }
        if (index < 0) return
        val lm = contentList.layoutManager
        when (lm) {
            is GridLayoutManager -> lm.scrollToPositionWithOffset(index, 0)
            is LinearLayoutManager -> lm.scrollToPositionWithOffset(index, 0)
            else -> contentList.scrollToPosition(index)
        }
    }

    private fun chooseCategories() {
        val all = Repo.allCategories(kind)
        if (all.isEmpty()) {
            toast("Load a playlist first")
            return
        }
        val hidden = Repo.prefs.hiddenCategories(kind)
        val labels = all.map { it.name }
        val checked = BooleanArray(all.size) { i -> !hidden.contains(all[i].id) }
        UiKit.multiChoose(this, "Categories to show", labels, checked) { result ->
            val nowHidden = HashSet<String>()
            for (i in all.indices) if (!result[i]) nowHidden.add(all[i].id)
            if (nowHidden.size == all.size) {
                toast("Leave at least one category ticked")
                return@multiChoose
            }
            Repo.prefs.setHiddenCategories(kind, nowHidden)
            currentCategoryId = Repo.ALL
            categoryAdapter.submit(buildCategories())
            refreshContent()
            toast(
                if (nowHidden.isEmpty()) "Showing every category"
                else "Hiding ${nowHidden.size} categories"
            )
        }
    }

    private fun buildCategories(): List<Category> {
        val head = mutableListOf(Category(Repo.ALL, getString(R.string.all_channels), kind))
        head.addAll(Repo.visibleCategories(kind))
        return head
    }

    private fun setUpContentList() {
        if (kind == Kind.LIVE) {
            contentList.layoutManager = LinearLayoutManager(this)
            channelAdapter = ChannelAdapter(
                emptyList(),
                onClick = { channel ->
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                    })
                },
                onFavouriteToggle = { channel ->
                    val added = Repo.prefs.toggleFavourite(channel.id)
                    toast(if (added) "Added to favourites" else "Removed from favourites")
                }
            )
            contentList.adapter = channelAdapter
        } else {
            contentList.layoutManager = GridLayoutManager(this, POSTER_COLUMNS)
            posterAdapter = PosterAdapter(emptyList(), emptyList()) { index -> onPosterClicked(index) }
            contentList.adapter = posterAdapter
        }
    }

    private fun refreshContent() {
        val query = search.text.toString().trim()

        when (kind) {
            Kind.LIVE -> {
                val list = when {
                    query.isNotEmpty() -> Repo.searchChannels(query)
                    favouritesOnly -> Repo.favouriteChannels()
                    else -> Repo.channelsIn(currentCategoryId)
                }
                channelAdapter?.submit(list)
                currentNames = list.map { it.name }
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }

            Kind.VOD -> {
                val list = if (query.isNotEmpty()) Repo.searchMovies(query)
                else Repo.moviesIn(currentCategoryId)
                posterAdapter?.submitMovies(list)
                currentMovies = list
                currentNames = list.map { it.name }
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }

            else -> {
                val list = if (query.isNotEmpty()) Repo.searchSeries(query)
                else Repo.seriesIn(currentCategoryId)
                posterAdapter?.submitSeries(list)
                currentSeries = list
                currentNames = list.map { it.name }
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private var currentMovies: List<com.hga.media.data.VodItem> = emptyList()
    private var currentSeries: List<com.hga.media.data.SeriesItem> = emptyList()

    private fun onPosterClicked(index: Int) {
        if (kind == Kind.VOD) {
            val movie = currentMovies.getOrNull(index) ?: return
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, movie.url)
                putExtra(PlayerActivity.EXTRA_TITLE, movie.name)
            })
        } else {
            val series = currentSeries.getOrNull(index) ?: return
            openEpisodes(series)
        }
    }

    private fun openEpisodes(series: com.hga.media.data.SeriesItem) {
        title.text = series.name
        empty.visibility = View.GONE
        lifecycleScope.launch {
            val episodes = Repo.episodesFor(series.id)
            if (episodes.isEmpty()) {
                toast("No episodes listed for this series")
                return@launch
            }
            inEpisodeView = true
            contentList.layoutManager = LinearLayoutManager(this@BrowseActivity)
            contentList.adapter = EpisodeAdapter(episodes) { episode ->
                startActivity(Intent(this@BrowseActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, episode.url)
                    putExtra(PlayerActivity.EXTRA_TITLE, "${series.name} · ${episode.title}")
                })
            }
            contentList.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // The category filter may have been changed in Settings while we were away.
        if (Repo.loaded && !inEpisodeView) {
            categoryAdapter.submit(buildCategories())
            currentCategoryId = Repo.ALL
            refreshContent()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && inEpisodeView) {
            inEpisodeView = false
            title.setText(R.string.menu_series)
            setUpContentList()
            refreshContent()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            search.requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /** Four across fits the content pane on a 1080p TV without clipping. */
        private const val POSTER_COLUMNS = 4
        const val EXTRA_KIND = "kind"
        const val EXTRA_FAVOURITES = "favourites"
    }
}
