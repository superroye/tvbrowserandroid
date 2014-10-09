/*
 * TV-Browser for Android
 * Copyright (C) 2013-2014 René Mach (rene@tvbrowser.org)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to use, copy, modify or merge the Software,
 * furthermore to publish and distribute the Software free of charge without modifications and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.tvbrowser.widgets;

import java.util.Calendar;

import org.tvbrowser.settings.PrefUtils;
import org.tvbrowser.settings.SettingConstants;
import org.tvbrowser.tvbrowser.CompatUtils;
import org.tvbrowser.tvbrowser.InfoActivity;
import org.tvbrowser.tvbrowser.R;
import org.tvbrowser.tvbrowser.TvBrowser;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * A widget for currently running programs.
 * 
 * @author René Mach
 */
public class RunningProgramsListWidget extends AppWidgetProvider {
  @Override
  public void onReceive(Context context, Intent intent) {
    if((AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction()) || SettingConstants.UPDATE_RUNNING_APP_WIDGET.equals(intent.getAction())) && intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) && 
        intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID) != AppWidgetManager.INVALID_APPWIDGET_ID) {
      AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context.getApplicationContext());
      
      int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID);
      onUpdate(context, appWidgetManager, new int[] {appWidgetId});
      appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.running_widget_list_view);
    }
    else {
      super.onReceive(context, intent);
    }
  }
  
  @Override
  public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
    final int n = appWidgetIds.length;
    
    for(int i = 0; i < n; i++) {
      int appWidgetId = appWidgetIds[i];
      
      Intent intent = new Intent(context, RunningProgramsRemoteViewsService.class);
      intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
      
      RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.running_programs_widget);
      CompatUtils.setRemoteViewsAdapter(views, appWidgetId, R.id.running_widget_list_view, intent);
      views.setEmptyView(R.id.running_widget_list_view, R.id.running_widget_empty_text);
      
      Intent tvb = new Intent(context, TvBrowser.class);
            
      PendingIntent tvbstart = PendingIntent.getActivity(context, 0, tvb, PendingIntent.FLAG_UPDATE_CURRENT);
      views.setOnClickPendingIntent(R.id.running_widget_header, tvbstart);
      
      SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
          
      int currentValue = pref.getInt(appWidgetId + "_" + context.getString(R.string.WIDGET_CONFIG_RUNNING_TIME), context.getResources().getInteger(R.integer.widget_congig_running_time_default));

      if(currentValue >= 0) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, currentValue / 60);
        cal.set(Calendar.MINUTE, currentValue % 60);
        
        views.setTextViewText(R.id.running_widget_header, DateFormat.getTimeFormat(context).format(cal.getTime()));
      }
      else {
        views.setTextViewText(R.id.running_widget_header, context.getString(R.string.widget_running_title));
      }
      
      Intent config = new Intent(context, InfoActivity.class);
      config.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
      
      PendingIntent timeSelection = PendingIntent.getActivity(context, appWidgetId, config, PendingIntent.FLAG_UPDATE_CURRENT);
      views.setOnClickPendingIntent(R.id.running_widget_time, timeSelection);
      
      Intent templateIntent = new Intent(SettingConstants.HANDLE_APP_WIDGET_CLICK);
      templateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
      
      PendingIntent templatePendingIntent = PendingIntent.getBroadcast(context, appWidgetId, templateIntent, PendingIntent.FLAG_UPDATE_CURRENT);
      
      views.setPendingIntentTemplate(R.id.running_widget_list_view, templatePendingIntent);
      
      appWidgetManager.updateAppWidget(appWidgetId, views);
    }
  }
}