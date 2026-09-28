package net.badgersmc.nexus.paper.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.badgersmc.nexus.scheduler.NexusScheduler
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Convenience base for paginated list browsers. Subclass to provide:
 *
 * - The current list of items (typed [T]).
 * - How to render a single item as an [ItemStack] (typically using
 *   [itemStack] + your LangService).
 * - The control row icons (prev, next, etc.) — defaults work but can be
 *   overridden.
 *
 * Layout is fixed: top `rows-1` rows are the paginated content (9 columns
 * each), bottom row carries page controls. For more elaborate layouts
 * subclass [LivePollingMenu] directly.
 */
abstract class PaginatedListMenu<T>(
    scheduler: NexusScheduler,
    rows: Int = 6,
    refreshTicks: Long = 20L
) : LivePollingMenu(scheduler, rows, refreshTicks) {

    init {
        require(rows in 2..6) {
            "PaginatedListMenu rows must be between 2 and 6 (got $rows) — one row is reserved for controls"
        }
    }

    private val contentRows = rows - 1
    private val controlRowY = rows - 1
    private val itemsPerPage = contentRows * 9

    private var currentPage: Int = 0

    /** Source list. Called on every render — keep it cheap or cache externally. */
    protected abstract fun items(): List<T>

    /** Render a single list element as an [ItemStack]. */
    protected abstract fun renderEntry(item: T): ItemStack

    /** Click handler for an entry. Default: no-op. */
    protected open fun onEntryClick(player: Player, item: T) {}

    /** Icon for the "previous page" control. */
    protected abstract fun prevIcon(): ItemStack
    /** Icon for the "next page" control. */
    protected abstract fun nextIcon(): ItemStack
    /** Icon for the centre page indicator. Receives `current` (1-based) and `total`. */
    protected abstract fun pageIndicatorIcon(current: Int, total: Int, items: Int): ItemStack
    /** Icon for the close button on the far right. */
    protected abstract fun closeIcon(): ItemStack

    override fun render(gui: ChestGui) {
        val list = items()
        val pageCount = ((list.size + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)
        if (currentPage >= pageCount) currentPage = pageCount - 1
        if (currentPage < 0) currentPage = 0

        gui.panes.clear()

        val itemsPane = PaginatedPane(9, contentRows)
        for (pageIdx in 0 until pageCount) {
            val pagePane = OutlinePane(9, contentRows, Pane.Priority.LOWEST)
            val slice = list.drop(pageIdx * itemsPerPage).take(itemsPerPage)
            for (entry in slice) {
                pagePane.addItem(GuiItem(renderEntry(entry)) { event ->
                    event.isCancelled = true
                    (event.whoClicked as? Player)?.let { onEntryClick(it, entry) }
                })
            }
            itemsPane.addPane(pageIdx, Slot.fromXY(0, 0), pagePane)
        }
        itemsPane.page = currentPage
        gui.addPane(Slot.fromXY(0, 0), itemsPane)

        gui.addPane(Slot.fromXY(0, controlRowY), buildControls(gui, pageCount, list.size))
    }

    private fun buildControls(gui: ChestGui, pageCount: Int, total: Int): StaticPane {
        val pane = StaticPane(9, 1)

        pane.addItem(GuiItem(prevIcon()) {
            it.isCancelled = true
            if (currentPage > 0) { currentPage--; render(gui); gui.update() }
        }, 0, 0)

        pane.addItem(GuiItem(pageIndicatorIcon(currentPage + 1, pageCount, total)) {
            it.isCancelled = true
        }, 4, 0)

        pane.addItem(GuiItem(nextIcon()) {
            it.isCancelled = true
            if (currentPage < pageCount - 1) { currentPage++; render(gui); gui.update() }
        }, 6, 0)

        pane.addItem(GuiItem(closeIcon()) {
            it.isCancelled = true
            (it.whoClicked as? Player)?.closeInventory()
        }, 8, 0)

        return pane
    }

    /** Page reset hook for subclasses that change filter/sort. */
    protected fun resetPage() { currentPage = 0 }
}

/** Marker placeholder for unused [Component] params in subclasses; harmless utility. */
@Suppress("unused")
internal fun Component.discard() = Unit
