package tech.mmarca.openwatch.wear.tiles

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import tech.mmarca.openwatch.wear.MainActivity
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.TodayTotals
import tech.mmarca.openwatch.wear.WearReadings
import tech.mmarca.openwatch.wear.WearReadingsSource
import tech.mmarca.openwatch.wear.R
import java.text.NumberFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class MainVitalsTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // The store is read off the main thread: a day of heart rate is thousands of rows.
        val future = CompletableFuture.supplyAsync {
            val totals = todayTotals(this)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder()
                                        .setRoot(buildTileLayout(this, requestParams.deviceConfiguration, totals))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        }
        return SettableListenableFuture(future)
    }

    /** The day's totals from the watch's own store; nothing is shown as "--". */
    private fun todayTotals(context: Context): TodayTotals {
        val store = MetricStore(context)
        return try {
            WearReadings.totals(WearReadingsSource(store).today())
        } finally {
            store.close()
        }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()

        val future = CompletableFuture<ResourceBuilders.Resources>()
        future.complete(resources)
        return SettableListenableFuture(future)
    }

    private fun buildTileLayout(
        context: Context,
        deviceParameters: DeviceParametersBuilders.DeviceParameters,
        totals: TodayTotals,
    ): LayoutElementBuilders.LayoutElement {
        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build()
            )
            .build()

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setOnClick(launchAction)
            .setId("open_app")
            .build()

        val clickModifiers = ModifiersBuilders.Modifiers.Builder()
            .setClickable(clickable)
            .build()

        val primaryColor = 0xFF10B981.toInt()

        val headerText = Text.Builder(context, context.getString(R.string.app_name))
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .setColor(ColorBuilders.argb(primaryColor))
            .build()

        val titleText = Text.Builder(context, context.getString(R.string.dashboard_today))
            .setTypography(Typography.TYPOGRAPHY_TITLE2)
            .build()

        val summaryText = Text.Builder(context, todaySummary(context, totals))
            .setTypography(Typography.TYPOGRAPHY_BODY1)
            .build()

        val chip = CompactChip.Builder(
            context,
            context.getString(R.string.tile_open),
            clickable,
            deviceParameters,
        )
            .setChipColors(
                ChipColors(
                    ColorBuilders.argb(primaryColor),
                    ColorBuilders.argb(0xFF000000.toInt()),
                )
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(clickModifiers)
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(headerText)
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(8f))
                            .build()
                    )
                    .addContent(titleText)
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(4f))
                            .build()
                    )
                    .addContent(summaryText)
                    .addContent(
                        LayoutElementBuilders.Spacer.Builder()
                            .setHeight(dp(12f))
                            .build()
                    )
                    .addContent(chip)
                    .build()
            )
            .build()
    }

    private class SettableListenableFuture<V>(
        private val delegate: CompletableFuture<V>,
    ) : ListenableFuture<V> {
        override fun addListener(listener: Runnable, executor: Executor) {
            delegate.thenAcceptAsync({ listener.run() }, executor)
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = delegate.cancel(mayInterruptIfRunning)
        override fun isCancelled(): Boolean = delegate.isCancelled
        override fun isDone(): Boolean = delegate.isDone
        override fun get(): V = delegate.get()
        override fun get(timeout: Long, unit: TimeUnit): V = delegate.get(timeout, unit)
    }

    private fun todaySummary(context: Context, totals: TodayTotals): String {
        val steps = totals.steps?.let {
            context.getString(R.string.tile_value, NumberFormat.getIntegerInstance().format(it), context.getString(R.string.unit_steps))
        } ?: context.getString(R.string.metric_no_value)
        val heart = totals.heartRate?.let {
            context.getString(R.string.tile_value, it.toString(), context.getString(R.string.unit_bpm))
        } ?: context.getString(R.string.metric_no_value)
        return context.getString(R.string.tile_today_summary, steps, heart)
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        /** Read again at most this often while the tile is shown. */
        private const val FRESHNESS_MILLIS = 5 * 60 * 1000L
    }
}
