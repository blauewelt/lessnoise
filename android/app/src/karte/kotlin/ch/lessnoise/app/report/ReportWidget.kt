package ch.lessnoise.app.report

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import ch.lessnoise.app.R

/**
 * 0.8.4, editions with the map: the home-screen widget «Laut! Melden» — one button (2×1 cells,
 * res/layout/report_widget.xml of src/karte) that opens the report dialog ([ReportNow]). Plain
 * RemoteViews, no Glance. It shows nothing from the app (no level, no status) and never updates by
 * itself (updatePeriodMillis = 0): the only thing it does is the tap. Registered in the manifests of
 * src/public and src/laborKarte only.
 */
class ReportWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.report_widget)
        views.setOnClickPendingIntent(R.id.report_widget_button, ReportNow.pendingIntent(context))
        manager.updateAppWidget(ids, views)
    }
}
