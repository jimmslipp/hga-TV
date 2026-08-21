package com.hga.media.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hga.media.R
import com.hga.media.data.Category
import com.hga.media.data.Channel
import com.hga.media.data.Episode
import com.hga.media.data.Repo
import com.hga.media.data.SeriesItem
import com.hga.media.data.VodItem
import com.hga.media.util.ImageLoader

/**
 * Base class that makes adapter updates safe.
 *
 * Telling a RecyclerView to redraw while it is still measuring or scrolling
 * throws immediately - which is exactly what happens when a D-pad moves focus
 * quickly down a list. Everything here is queued to the next frame if the list
 * is busy, so fast remote presses can never crash the app.
 */
abstract class SafeAdapter<VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {

    private var host: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        host = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        host = null
    }

    protected fun safely(action: () -> Unit) {
        val rv = host
        if (rv != null && (rv.isComputingLayout || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE)) {
            rv.post { action() }
        } else {
            action()
        }
    }
}

class CategoryAdapter(
    private var items: List<Category>,
    private val onSelect: (Category, Int) -> Unit
) : SafeAdapter<CategoryAdapter.VH>() {

    var selectedIndex = 0
        private set

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view as TextView
    }

    fun submit(newItems: List<Category>) {
        items = newItems
        selectedIndex = 0
        safely { notifyDataSetChanged() }
    }

    fun itemAt(position: Int): Category? = items.getOrNull(position)

    fun select(index: Int) {
        if (index == selectedIndex || index !in items.indices) return
        val old = selectedIndex
        selectedIndex = index
        safely {
            notifyItemChanged(old)
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.name.isSelected = position == selectedIndex

        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnClickListener
            select(p)
            items.getOrNull(p)?.let { cat -> onSelect(cat, p) }
        }

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION || p == selectedIndex) return@setOnFocusChangeListener
            // Wait for the current layout pass to finish before changing anything.
            view.post {
                if (p !in items.indices) return@post
                select(p)
                onSelect(items[p], p)
            }
        }
    }
}

class ChannelAdapter(
    private var items: List<Channel>,
    private val onClick: (Channel) -> Unit,
    private val onFavouriteToggle: ((Channel) -> Unit)? = null
) : SafeAdapter<ChannelAdapter.VH>() {

    var highlightId: String? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView = view.findViewById(R.id.chNum)
        val logo: ImageView = view.findViewById(R.id.chLogo)
        val name: TextView = view.findViewById(R.id.chName)
        val now: TextView = view.findViewById(R.id.chNow)
        val progress: ProgressBar = view.findViewById(R.id.chProgress)
        val fav: ImageView = view.findViewById(R.id.chFav)
    }

    fun submit(newItems: List<Channel>) {
        items = newItems
        safely { notifyDataSetChanged() }
    }

    fun itemAt(position: Int): Channel? = items.getOrNull(position)

    fun indexOfId(id: String?): Int = items.indexOfFirst { it.id == id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = items[position]
        holder.num.text = channel.num.toString()
        holder.name.text = channel.name
        ImageLoader.load(holder.logo, channel.logo, R.drawable.ph_channel)

        val (current, _) = Repo.guide.nowNext(channel.epgId, channel.name)
        if (current != null) {
            holder.now.visibility = View.VISIBLE
            holder.now.text = current.title
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = current.progressPercent()
        } else {
            holder.now.visibility = View.GONE
            holder.progress.visibility = View.GONE
        }

        holder.fav.visibility =
            if (Repo.prefs.isFavourite(channel.id)) View.VISIBLE else View.GONE

        holder.itemView.isSelected = channel.id == highlightId
        holder.itemView.setOnClickListener { onClick(channel) }
        holder.itemView.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            onFavouriteToggle?.invoke(channel)
            if (p != RecyclerView.NO_POSITION) safely { notifyItemChanged(p) }
            true
        }
    }
}

/** One grid adapter serving both movies and series. */
class PosterAdapter(
    private var titles: List<String>,
    private var covers: List<String?>,
    private val onClick: (Int) -> Unit
) : SafeAdapter<PosterAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.posterImage)
        val name: TextView = view.findViewById(R.id.posterName)
    }

    fun submitMovies(list: List<VodItem>) {
        titles = list.map { it.name }
        covers = list.map { it.cover }
        safely { notifyDataSetChanged() }
    }

    fun submitSeries(list: List<SeriesItem>) {
        titles = list.map { it.name }
        covers = list.map { it.cover }
        safely { notifyDataSetChanged() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
    )

    override fun getItemCount() = titles.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.name.text = titles[position]
        ImageLoader.load(holder.image, covers.getOrNull(position), R.drawable.ph_poster)
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
    }
}

class EpisodeAdapter(
    private var items: List<Episode>,
    private val onClick: (Episode) -> Unit
) : SafeAdapter<EpisodeAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val num: TextView = view.findViewById(R.id.epNum)
        val title: TextView = view.findViewById(R.id.epTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.num.text = "S%02dE%02d".format(ep.season, ep.episode)
        holder.title.text = ep.title
        holder.itemView.setOnClickListener { onClick(ep) }
    }
}
