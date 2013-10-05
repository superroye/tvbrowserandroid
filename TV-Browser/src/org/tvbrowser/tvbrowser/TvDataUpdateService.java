package org.tvbrowser.tvbrowser;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.tvbrowser.content.TvBrowserContentProvider;
import org.tvbrowser.settings.SettingConstants;

import android.app.DownloadManager;
import android.app.NotificationManager;
import android.app.Service;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

public class TvDataUpdateService extends Service {
  public static final String TAG = "TV_DATA_UPDATE_SERVICE";
  public static final String DAYS_TO_LOAD = "DAYS_TO_LOAD";
  
  public static final String TYPE = "TYPE";
  public static final int TV_DATA_TYPE = 1;
  public static final int CHANNEL_TYPE = 2;
  
  private IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
  
  private HashMap<Long,ChannelUpdate> downloadIDs;
  private boolean updateRunning;
  private ExecutorService mThreadPool;
  private Handler mHandler;
  private int mDayStart;
  private int mDayEnd;
  
  private int mNotifyID = 1;
  private NotificationCompat.Builder mBuilder;
  private int mCurrentDownloadCount;
  private int mDaysToLoad;
    
  private static final Integer[] FRAME_ID_ARR;
  
  static {
    FRAME_ID_ARR = new Integer[253];
    
    for(int i = 0; i < FRAME_ID_ARR.length; i++) {
      FRAME_ID_ARR[i] = Integer.valueOf(i+2);
    }
  }
    
  @Override
  public void onCreate() {
    super.onCreate();
    
    mDaysToLoad = 2;
    
    mBuilder = new NotificationCompat.Builder(this);
    mBuilder.setSmallIcon(R.drawable.ic_launcher);
    mBuilder.setOngoing(true);
    
    mHandler = new Handler();
  }
  
  @Override
  public IBinder onBind(Intent intent) {
    // TODO Auto-generated method stub
    return null;
  }
  
  @Override
  public int onStartCommand(final Intent intent, int flags, int startId) {
    // TODO Auto-generated method stub
    
    
    new Thread() {
      public void run() {
        setPriority(MIN_PRIORITY);
        
        if(intent.getIntExtra(TYPE, TV_DATA_TYPE) == TV_DATA_TYPE) {
          mDaysToLoad = intent.getIntExtra(DAYS_TO_LOAD, 2);
          updateTvData();
        }
        else if(intent.getIntExtra(TYPE, TV_DATA_TYPE) == CHANNEL_TYPE) {
          updateChannels();
        }
      }
    }.start();
        
    return Service.START_NOT_STICKY;
  }
  
  private void updateChannels() {
    NotificationManager notification = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
    mBuilder.setProgress(100, 0, true);
    mBuilder.setContentTitle(getResources().getText(R.string.channel_notification_title));
    mBuilder.setContentText(getResources().getText(R.string.channel_notification_text));
    notification.notify(mNotifyID, mBuilder.build());
    
    final DownloadManager download = (DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
    
    Uri uri = Uri.parse("http://www.tvbrowser.org/listings/groups.txt");
    
    DownloadManager.Request request = new Request(uri);
    
    final long reference = download.enqueue(request);
    
    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, android.content.Intent intent) {
        long receiveReference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        
        if(reference == receiveReference) {
          unregisterReceiver(this);
          
          new Thread() {
            public void run() {
              updateGroups(download,reference);
            }
          }.start();
        }
      };
    };
    
    registerReceiver(receiver, filter);
  }
  
  private void updateGroups(final DownloadManager download, long reference) {
    final ArrayList<GroupInfo> channelMirrors = new ArrayList<GroupInfo>();
    
    try {
      BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(download.openDownloadedFile(reference).getFileDescriptor())));
      
      ContentResolver cr = getContentResolver();

      String line = null;
      
      while((line = in.readLine()) != null) {
        String[] parts = line.split(";");
        
        // Construct a where clause to make sure we don't already have ths group in the provider.
        String w = TvBrowserContentProvider.GROUP_KEY_DATA_SERVICE_ID + " = '" + SettingConstants.EPG_FREE_KEY + "' AND " + TvBrowserContentProvider.GROUP_KEY_GROUP_ID + " = '" + parts[0] + "'";
        
        // If the group is new, insert it into the provider.
        Cursor query = cr.query(TvBrowserContentProvider.CONTENT_URI_GROUPS, null, w, null, null);
        
        ContentValues values = new ContentValues();
        
        values.put(TvBrowserContentProvider.GROUP_KEY_DATA_SERVICE_ID, SettingConstants.EPG_FREE_KEY);
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_ID, parts[0]);
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_NAME, parts[1]);
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_PROVIDER, parts[2]);
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_DESCRIPTION, parts[3]);
        
        StringBuilder builder = new StringBuilder(parts[4]);
        
        for(int i = 5; i < parts.length; i++) {
          builder.append(";");
          builder.append(parts[i]);
        }
        
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_MIRRORS, builder.toString());
        
        
        
        if(query == null || query.getCount() == 0) {
          // The group is not already known, so insert it
          Uri insert = cr.insert(TvBrowserContentProvider.CONTENT_URI_GROUPS, values);
          
          GroupInfo test = loadChannelForGroup(download, cr.query(insert, null, null, null, null));
          Log.d(TAG, " GROUPINFO " + String.valueOf(test));
          if(test != null) {
            channelMirrors.add(test);
          }
        }
        else {
          cr.update(TvBrowserContentProvider.CONTENT_URI_GROUPS, values, w, null);
          
          GroupInfo test = loadChannelForGroup(download, cr.query(TvBrowserContentProvider.CONTENT_URI_GROUPS, null, w, null, null));
          Log.d(TAG, " GROUPINFO " + String.valueOf(test));
          if(test != null) {
            channelMirrors.add(test);
          }
        }
        
        query.close();
      }
      
      in.close();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      Log.d(TAG, "EXCEPTION", e);
    }
    
    Log.d(TAG, String.valueOf(channelMirrors.isEmpty()));
    if(!channelMirrors.isEmpty()) {
      mThreadPool =  Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors(), 2));
      
      final HashMap<Long,GroupInfo> requestIDMap = new HashMap<Long, TvDataUpdateService.GroupInfo>(); 
      
      BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
          long receiveReference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
          
          if(requestIDMap.containsKey(Long.valueOf(receiveReference))) {
            final GroupInfo info = requestIDMap.remove(Long.valueOf(receiveReference));
            final long requestId = receiveReference;
            
            mThreadPool.execute(new Thread() {
              public void run() {
                addChannels(download,requestId,info);//requestId,keyID,groupId);
              }
            });
          }
          
          if(requestIDMap.isEmpty()) {
            unregisterReceiver(this);
            new Thread() {
              public void run() {
                mThreadPool.shutdown();
                try {
                  Log.d("info", "await termination for channels");
                  mThreadPool.awaitTermination(10, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
                }
                NotificationManager notification = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                notification.cancel(mNotifyID);

                LocalBroadcastManager.getInstance(TvDataUpdateService.this).sendBroadcast(new Intent(SettingConstants.CHANNEL_DOWNLOAD_COMPLETE));
                stopSelf();
              }
            }.start();
          }
        };
      };
      
      registerReceiver(receiver, filter);
      
      for(GroupInfo info : channelMirrors) {
        Log.d(TAG, info.getUrl());
        Request request = new Request(Uri.parse(info.getUrl()));
        long id = download.enqueue(request);
        
        requestIDMap.put(Long.valueOf(id), info);
      }
    }
  }
  
  private synchronized GroupInfo loadChannelForGroup(final DownloadManager download, final Cursor cursor) { 
    int index = cursor.getColumnIndex(TvBrowserContentProvider.GROUP_KEY_GROUP_MIRRORS);
    
    if(index >= 0) {
      cursor.moveToFirst();
      
      String temp = cursor.getString(index);
      
      index = cursor.getColumnIndex(TvBrowserContentProvider.GROUP_KEY_GROUP_ID);
      final String groupId = cursor.getString(index);
      
      String[] mirrors = null;
      
      if(temp.contains(";")) {
        mirrors = temp.split(";");
      }
      else {
        mirrors = new String[1];
        mirrors[0] = temp;
      }
      
      int idIndex = cursor.getColumnIndex(TvBrowserContentProvider.KEY_ID);
      final int keyID = cursor.getInt(idIndex);
      
      for(String mirror : mirrors) {
        
        if(isConnectedToServer(mirror,5000)) {
          if(!mirror.endsWith("/")) {
            mirror += "/";
          }
          
          return new GroupInfo(mirror+groupId+"_channellist.gz",keyID,groupId);
        }
      }
      
      cursor.close();
    }
    
    return null;
  }
  
  private class GroupInfo {
    private String mUrl;
    private int mUniqueGroupID;
    private String mGroupID;
    
    public GroupInfo(String url, int uniqueGroupID, String groupID) {
      mUrl = url;
      mUniqueGroupID = uniqueGroupID;
      mGroupID = groupID;
    }
    
    public String getUrl() {
      return mUrl;
    }
    
    public int getUniqueGroupID() {
      return mUniqueGroupID;
    }
    
    public String getGroupID() {
      return mGroupID;
    }
  }
  
  // Cursor contains the channel group
  public void addChannels(DownloadManager download, long reference, GroupInfo info) {
    try {
      BufferedReader read = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(download.openDownloadedFile(reference).getFileDescriptor())),"ISO-8859-1"));
      
      String line = null;
      
      while((line = read.readLine()) != null) {
        String[] parts = line.split(";");
        
        String baseCountry = parts[0];
        String timeZone = parts[1];
        String channelId = parts[2];
        String name = parts[3];
        String copyright = parts[4];
        String website = parts[5];
        String logoUrl = parts[6];
        int category = Integer.parseInt(parts[7]);
        
        StringBuilder fullName = new StringBuilder();
        
        int i = 8;
        
        if(parts.length > i) {
            do {
              fullName.append(parts[i]);
              fullName.append(";");
            }while(!parts[i++].endsWith("\""));
            
            fullName.deleteCharAt(fullName.length()-1);
        }
        
        if(fullName.length() == 0) {
          fullName.append(name);
        }
        
        String allCountries = baseCountry;
        String joinedChannel = "";
        
        if(parts.length > i) {
          allCountries = parts[i++];
        }
        
        if(parts.length > i) {
          joinedChannel = parts[i];
        }
        
        String where = TvBrowserContentProvider.GROUP_KEY_GROUP_ID + " = " + info.mUniqueGroupID + " AND " + TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID + " = '" + channelId + "'";
        
        ContentResolver cr = getContentResolver();
        
        Cursor query = cr.query(TvBrowserContentProvider.CONTENT_URI_CHANNELS, null, where, null, null);
        
        ContentValues values = new ContentValues();
        
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_ID, info.getUniqueGroupID());
        values.put(TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID, channelId);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_BASE_COUNTRY, baseCountry);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_TIMEZONE, timeZone);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_NAME, name);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_COPYRIGHT, copyright);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_WEBSITE, website);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_LOGO_URL, logoUrl);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_CATEGORY, category);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_FULL_NAME, fullName.toString().replaceAll("\"", ""));
        values.put(TvBrowserContentProvider.CHANNEL_KEY_ALL_COUNTRIES, allCountries);
        values.put(TvBrowserContentProvider.CHANNEL_KEY_JOINED_CHANNEL_ID, joinedChannel);
        
        if(query == null || query.getCount() == 0) {
          // add channel
          cr.insert(TvBrowserContentProvider.CONTENT_URI_CHANNELS, values);
        }
        else {
          // update channel
          cr.update(TvBrowserContentProvider.CONTENT_URI_CHANNELS, values, where, null);
        }
        
        query.close();
      }
      read.close();
    } catch (FileNotFoundException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private boolean isConnectedToServer(String url, int timeout) {
    try {
      URL myUrl = new URL(url);
      
      URLConnection connection;
      connection = myUrl.openConnection();
      connection.setConnectTimeout(timeout);
      
      HttpURLConnection httpConnection = (HttpURLConnection)connection;
      
      if(httpConnection != null) {
        int responseCode = httpConnection.getResponseCode();
      
        return responseCode == HttpURLConnection.HTTP_OK;
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
    return false;
  }
  
  /**
   * Calculate the end times of programs that are missing end time in the data.
   */
  private void calculateMissingEnds() {
    NotificationManager notification = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
    mBuilder.setProgress(100, 0, true);
    mBuilder.setContentText(getResources().getText(R.string.update_notification_calculate));
    notification.notify(mNotifyID, mBuilder.build());
    
    // Only ID, channel ID, start and end time are needed for update, so use only these columns
    String[] projection = {
        TvBrowserContentProvider.KEY_ID,
        TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID,
        TvBrowserContentProvider.DATA_KEY_STARTTIME,
        TvBrowserContentProvider.DATA_KEY_ENDTIME,
    };
    
    Cursor c = getContentResolver().query(TvBrowserContentProvider.CONTENT_URI_DATA_UPDATE, projection, null, null, TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID + " , " + TvBrowserContentProvider.DATA_KEY_STARTTIME);
    
    Log.d(TAG, "DATA-COUNT " + c.getCount());
    
    // only if there are data update it
    if(c.getCount() > 0) {
      c.moveToFirst();
            
      do {
        long progID = c.getLong(0);
        int channelKey = c.getInt(1);
        long end = c.getLong(3);
        
        c.moveToNext();
        
        // only if end is not set update it (maybe all should be updated except programs that contains a length value)
        if(end == 0) {
          long nextStart = c.getLong(2);
          
          if(c.getInt(1) == channelKey) {
            ContentValues values = new ContentValues();
            values.put(TvBrowserContentProvider.DATA_KEY_ENDTIME, nextStart);
            
            getContentResolver().update(ContentUris.withAppendedId(TvBrowserContentProvider.CONTENT_URI_DATA_UPDATE, progID), values, null, null);
          }
        }
      }while(!c.isLast());
    }
    
    c.close();
    
    updateRunning = false;
    
    // Data update complete inform user
    mHandler.post(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(getApplicationContext(), R.string.update_complete, Toast.LENGTH_LONG).show();
      }
    });
    
    TvBrowserContentProvider.INFORM_FOR_CHANGES = true;
    getApplicationContext().getContentResolver().notifyChange(TvBrowserContentProvider.CONTENT_URI_DATA, null);
    
    updateFavorites();
    
    mBuilder.setProgress(0, 0, false);
    notification.cancel(mNotifyID);
    
    stopSelf();
  }
  
  private void updateFavorites() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    
    Set<String> favoritesSet = prefs.getStringSet(SettingConstants.FAVORITE_LIST, new HashSet<String>());
        
    for(String favorite : favoritesSet) {
      String[] values = favorite.split(";;");
      
      Favorite fav = new Favorite(values[0], values[1], Boolean.valueOf(values[2]));
      
      Favorite.updateFavoriteMarking(getApplicationContext(), getContentResolver(), fav);
    }
  }
  
  private int getDayStart() {
    return mDayStart;
  }
  
  private int getDayEnd() {
    return mDayEnd;
  }
  
  private void getUserDayValues() {
    mDayStart = 0;
    mDayEnd = 24;
  }
  
  private void updateTvData() {
    if(!updateRunning) {
      mCurrentDownloadCount = 0;
      mBuilder.setContentTitle(getResources().getText(R.string.update_notification_title));
      mBuilder.setContentText(getResources().getText(R.string.update_notification_text));
      
      final NotificationManager notification = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
      notification.notify(mNotifyID, mBuilder.build());
      
      getUserDayValues();
      TvBrowserContentProvider.INFORM_FOR_CHANGES = false;
      updateRunning = true;
      
      ContentResolver cr = getContentResolver();
      
      StringBuilder where = new StringBuilder(TvBrowserContentProvider.CHANNEL_KEY_SELECTION);
      where.append(" = 1");
      
      final ArrayList<ChannelUpdate> downloadList = new ArrayList<ChannelUpdate>();
      ArrayList<String> downloadMirrorList = new ArrayList<String>();
      
      Cursor channelCursor = cr.query(TvBrowserContentProvider.CONTENT_URI_CHANNELS, null, where.toString(), null, TvBrowserContentProvider.GROUP_KEY_GROUP_ID);
      
      String[] versionColumns = {TvBrowserContentProvider.VERSION_KEY_BASE_VERSION, TvBrowserContentProvider.VERSION_KEY_MORE0016_VERSION, TvBrowserContentProvider.VERSION_KEY_MORE1600_VERSION, TvBrowserContentProvider.VERSION_KEY_PICTURE0016_VERSION, TvBrowserContentProvider.VERSION_KEY_PICTURE1600_VERSION};
      
      if(channelCursor.getCount() > 0) {
        channelCursor.moveToFirst();
        
        int lastGroup = -1;
        Mirror mirror = null;
        String groupId = null;
        Summary summary = null;
        
        do {
          int groupKey = channelCursor.getInt(channelCursor.getColumnIndex(TvBrowserContentProvider.GROUP_KEY_GROUP_ID));
          int channelKey = channelCursor.getInt(channelCursor.getColumnIndex(TvBrowserContentProvider.KEY_ID));
          String timeZone = channelCursor.getString(channelCursor.getColumnIndex(TvBrowserContentProvider.CHANNEL_KEY_TIMEZONE));
          
          if(lastGroup != groupKey) {
            Cursor group = cr.query(ContentUris.withAppendedId(TvBrowserContentProvider.CONTENT_URI_GROUPS, groupKey), null, null, null, null);
            Log.d("MIRR", " XXX " + String.valueOf(groupKey) + " " + String.valueOf(group.getCount()));
            if(group.getCount() > 0) {
              group.moveToFirst();
              
              groupId = group.getString(group.getColumnIndex(TvBrowserContentProvider.GROUP_KEY_GROUP_ID));
              String mirrorURL = group.getString(group.getColumnIndex(TvBrowserContentProvider.GROUP_KEY_GROUP_MIRRORS));
              
              Mirror[] mirrors = Mirror.getMirrorsFor(mirrorURL);
              
              mirror = Mirror.getMirrorToUseForGroup(mirrors, groupId);                
              
              Log.d(TAG, mirrorURL);
              Log.d(TAG, " MIRROR " + mirror + " " + groupId + "_summary.gz");
              
              if(mirror != null) {
                summary = readSummary(mirror.getUrl() + groupId + "_summary.gz");
                downloadMirrorList.add(mirror.getUrl() + groupId + "_mirrorlist.gz");
              }
            }
            
            group.close();
          }
          
          if(summary != null) {
            ChannelFrame frame = summary.getChannelFrame(channelCursor.getString(channelCursor.getColumnIndex(TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID)));
            Log.d(TAG, " CHANNEL FRAME " + String.valueOf(frame) + " " + String.valueOf(channelCursor.getString(channelCursor.getColumnIndex(TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID))));
            if(frame != null) {
              Calendar startDate = summary.getStartDate();
              
              Calendar now = Calendar.getInstance();
              now.add(Calendar.DAY_OF_MONTH, -2);

              Calendar to = Calendar.getInstance();
              to.add(Calendar.DAY_OF_MONTH, mDaysToLoad);

              
              for(int i = 0; i < frame.getDayCount(); i++) {
                startDate.add(Calendar.DAY_OF_YEAR, 1);
                
                if(startDate.compareTo(now) >= 0 && startDate.compareTo(to) <= 0) {
                  int[] version = frame.getVersionForDay(i);
                  // load only base files
                  
                  Log.d(TAG, " VERSION " + version); 
                  if(version != null) {
                    long daysSince1970 = startDate.getTimeInMillis() / 24 / 60 / 60000;
                    
                    String versionWhere = TvBrowserContentProvider.VERSION_KEY_DAYS_SINCE_1970 + " = " + daysSince1970 + " AND " + TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID + " = " + channelKey;
                    
                    Cursor versions = getContentResolver().query(TvBrowserContentProvider.CONTENT_URI_DATA_VERSION, null, versionWhere, null, null);
                    
                    if(versions.getCount() > 0) {
                      versions.moveToFirst();
                    }
                    
                    for(int level = 0; level < 1; level++) {
                      int testVersion = 0;
                      
                      if(versions.getCount() > 0) {
                        testVersion = versions.getInt(versions.getColumnIndex(versionColumns[level]));
                      }
                      
                      Log.d("MIRR", testVersion +  " level version " + version[level] + " " + frame.getChannelID() + " " + startDate.getTime() + " " + daysSince1970);
                      
                      if(version[level] > testVersion) {
                        String month = String.valueOf(startDate.get(Calendar.MONTH)+1);
                        String day = String.valueOf(startDate.get(Calendar.DAY_OF_MONTH));
                        
                        if(month.length() == 1) {
                          month = "0" + month;
                        }
                        
                        if(day.length() == 1) {
                          day = "0" + day;
                        }
                        
                        StringBuilder dateFile = new StringBuilder();
                        dateFile.append(mirror.getUrl());
                        dateFile.append(startDate.get(Calendar.YEAR));
                        dateFile.append("-");
                        dateFile.append(month);
                        dateFile.append("-");
                        dateFile.append(day);
                        dateFile.append("_");
                        dateFile.append(frame.getCountry());
                        dateFile.append("_");
                        dateFile.append(frame.getChannelID());
                        dateFile.append("_");
                        dateFile.append(SettingConstants.LEVEL_NAMES[level]);
                        dateFile.append("_full.prog.gz");
                                              
                        downloadList.add(new ChannelUpdate(dateFile.toString(), channelKey, timeZone, startDate.getTimeInMillis()));
                     //   Log.d(TAG, " DOWNLOADS " + dateFile.toString());
                      }
                    }
                    
                    versions.close();
                  }
                }
              }
            }
          }
          
          lastGroup = groupKey;
        }while(channelCursor.moveToNext());
        
      }
      
      channelCursor.close();
      
      final DownloadManager download = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
      
      final int downloadCount = downloadMirrorList.size() + downloadList.size();
      
      final HashMap<Long, String> mirrorIDs = new HashMap<Long, String>();
      downloadIDs = new HashMap<Long,ChannelUpdate>();
      mThreadPool = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors(), 2));
      Log.d("info", " length " + String.valueOf(downloadList.size()));
      BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
          final long receiveReference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
          
          if(downloadIDs.containsKey(Long.valueOf(receiveReference))) {
            final ChannelUpdate update = downloadIDs.remove(Long.valueOf(receiveReference));
            Log.d("info", " channel update "+ update.getUrl());
            mThreadPool.execute(new Thread() {
              public void run() {
                updateData(download,receiveReference, update);
                mCurrentDownloadCount++;
                mBuilder.setProgress(downloadCount, mCurrentDownloadCount, false);
                notification.notify(mNotifyID, mBuilder.build());
              }
            });
          }
          else if(mirrorIDs.containsKey(Long.valueOf(receiveReference))) {
            String url = mirrorIDs.remove(Long.valueOf(receiveReference));
            updateMirror(new File(getExternalFilesDir(null),url.substring(url.lastIndexOf("/"))));
          }
          Log.d("info", String.valueOf(downloadIDs.isEmpty()));
          if(downloadIDs.isEmpty()) {
            new Thread() {
              public void run() {
                mThreadPool.shutdown();
                try {
                  Log.d("info", "await termination");
                  mThreadPool.awaitTermination(10, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  Log.d("info", " term ", e);
                }
                Log.d("info", "calculate missing length");
                mBuilder.setProgress(100, 0, true);
                notification.notify(mNotifyID, mBuilder.build());
                calculateMissingEnds();
              }
            }.start();
            
            unregisterReceiver(this);
          }
        };
      };
      
      registerReceiver(receiver, filter);
      
      mBuilder.setProgress(downloadCount, 0, false);
      notification.notify(mNotifyID, mBuilder.build());
      
      for(String mirror : downloadMirrorList) {
        Request request = new Request(Uri.parse(mirror));
        request.setDestinationInExternalFilesDir(getApplicationContext(), null, mirror.substring(mirror.lastIndexOf("/")));
        Log.d("MIRR", mirror);
        long id = download.enqueue(request);
        mirrorIDs.put(id, mirror);
      }
      
      for(int i = downloadList.size()-1; i >= 0; i--) {
        ChannelUpdate data = downloadList.get(i);
        Request request = new Request(Uri.parse(data.getUrl()));
        request.setDestinationInExternalFilesDir(getApplicationContext(), null, data.getUrl().substring(data.getUrl().lastIndexOf("/")));
        
        long id = download.enqueue(request);
        downloadIDs.put(id,data);
        downloadList.remove(i);
      }
    }
  }
  
  private void updateMirror(File mirrorFile) {
    if(mirrorFile.isFile()) {
      try {
        BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(mirrorFile))));
        
        StringBuilder mirrors = new StringBuilder();
        
        String line = null;
        
        while((line = in.readLine()) != null) {
          line = line.replace(";", "#");
          mirrors.append(line);
          mirrors.append(";");
        }
        
        if(mirrors.length() > 0) {
          mirrors.deleteCharAt(mirrors.length()-1);
        }
        
        ContentValues values = new ContentValues();
        
        values.put(TvBrowserContentProvider.GROUP_KEY_GROUP_MIRRORS, mirrors.toString());
        Log.d("MIRR", mirrors.toString() + " " + TvBrowserContentProvider.GROUP_KEY_GROUP_ID + " = " + mirrorFile.getName().substring(0, mirrorFile.getName().lastIndexOf("_")));
        getContentResolver().update(TvBrowserContentProvider.CONTENT_URI_GROUPS, values, TvBrowserContentProvider.GROUP_KEY_GROUP_ID + " = \"" + mirrorFile.getName().substring(0, mirrorFile.getName().lastIndexOf("_"))+"\"", null);
      } catch (FileNotFoundException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      
      mirrorFile.delete();
    }
  }
  
  private Summary readSummary(final String summaryurl) {
    final Summary summary = new Summary();
    Log.d("MIRR", "READ SUMMARY");

    URL url;
    try {
      url = new URL(summaryurl);
      Log.d("MIRR", " HIER " + summaryurl);
      URLConnection connection;
      
      connection = url.openConnection();
      connection.setRequestProperty("Accept-Encoding", "gzip,deflate");
      
      HttpURLConnection httpConnection = (HttpURLConnection)connection;
      if(httpConnection != null) {
      int responseCode = httpConnection.getResponseCode();
      Log.d("MIRR", String.valueOf(responseCode) + " " + String.valueOf(HttpURLConnection.HTTP_OK));
      if(responseCode == HttpURLConnection.HTTP_OK) {
        InputStream in = httpConnection.getInputStream();
        
        Map<String,List<String>>  map = httpConnection.getHeaderFields();
        
        for(String key : map.keySet()) {
          Log.d("MIRR", key + " " + map.get(key));
        }
        
       // if("gzip".equalsIgnoreCase(httpConnection.getHeaderField("Content-Encoding")) || "application/x-gzip".equalsIgnoreCase(httpConnection.getHeaderField("Content-Type"))) {
          try {
            in = new GZIPInputStream(in);
          }catch(IOException e2) {
            Log.d("MIRR", "GZIP", e2);
            // Guess it's not compressed if here
          }
       // }
        
        in = new BufferedInputStream(in);
        
        int version = in.read();
        Log.d("MIRR", "VERSION " + String.valueOf(version));
      //  in.
        
        //read file version
        summary.setVersion(version);
        
        long daysSince1970 = ((in.read() & 0xFF) << 16 ) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        
        summary.setStartDaySince1970(daysSince1970);
        
        summary.setLevels(in.read());
        
        int frameCount = (in.read() & 0xFF << 8) | (in.read() & 0xFF);
        Log.d("MIRR", "daysSince1970 " + String.valueOf(daysSince1970) + " frameCount " + String.valueOf(frameCount));
        for(int i = 0; i < frameCount; i++) {
          int byteCount = in.read();
          
          byte[] value = new byte[byteCount];
          
          in.read(value);
          
          String country = new String(value);
          
          byteCount = in.read();
          
          value = new byte[byteCount];
          
          in.read(value);
          
          String channelID = new String(value);
          
          int dayCount = in.read();
          
          ChannelFrame frame = new ChannelFrame(country, channelID, dayCount);
          
          for(int day = 0; day < dayCount; day++) {
            int[] values = new int[summary.getLevels()];
            
            for(int j = 0; j < values.length; j++) {
              values[j] = in.read();
            }
            
            frame.add(day, values);
          }
          
          summary.addChannelFrame(frame);
        }
        
      }
      }
    } catch (Exception e) {
      // TODO Auto-generated catch block
      Log.d("MIRR", "SUMMARY", e);
    }
    
    return summary;
  }
  
  private void updateData(DownloadManager download, long reference, ChannelUpdate update) {
    
    File dataFile = new File(download.getUriForDownloadedFile(reference).getPath()/*getExternalFilesDir(null),update.getUrl().substring(update.getUrl().lastIndexOf("/"))*/);
    
    try {
      
      
      BufferedInputStream in = new BufferedInputStream(new GZIPInputStream(new FileInputStream(dataFile)));
      
      byte fileVersion = (byte)in.read();
      byte dataVersion = (byte)in.read();
      
      int frameCount = in.read();
      
      ArrayList<Integer> missingFrameIDs = new ArrayList<Integer>(Arrays.asList(FRAME_ID_ARR));
      int maxFrameID = 0;
      
      for(int i = 0; i < frameCount; i++) {
        // ID of this program frame
        byte frameId = (byte)in.read();
        // number of program fields
        byte fieldCount = (byte)in.read();
        
        missingFrameIDs.remove(Integer.valueOf(frameId));
        
        maxFrameID = Math.max(maxFrameID, frameId);
        
        ContentValues values = new ContentValues();
        
        values.put(TvBrowserContentProvider.DATA_KEY_DATE_PROG_ID, frameId);
        values.put(TvBrowserContentProvider.DATA_KEY_UNIX_DATE, update.getDate());
        values.put(TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID, update.getChannelID());
        
        String where = TvBrowserContentProvider.DATA_KEY_DATE_PROG_ID + " = " + frameId +
            " AND " + TvBrowserContentProvider.DATA_KEY_UNIX_DATE + " = " + update.getDate() +
            " AND " + TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID + " = " + update.getChannelID();
        
        for(byte field = 0; field < fieldCount; field++) {
          byte fieldType = (byte)in.read();
          
          int dataCount = ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
          
          byte[] data = new byte[dataCount];
          
          in.read(data);
                    
          switch(fieldType) {
            case 1: {
                            int startTime = IOUtils.getIntForBytes(data);
                            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                            utc.setTimeInMillis(update.getDate());
                            
                            Calendar cal = Calendar.getInstance(update.getTimeZone());
                            cal.set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH));
                            cal.set(Calendar.MONTH, utc.get(Calendar.MONTH));
                            cal.set(Calendar.YEAR, utc.get(Calendar.YEAR));
                            
                            cal.set(Calendar.HOUR_OF_DAY, startTime / 60);
                            cal.set(Calendar.MINUTE, startTime % 60);
                            cal.set(Calendar.SECOND, 30);
                            
                            long time = (((long)(cal.getTimeInMillis() / 60000)) * 60000);
                            
                            values.put(TvBrowserContentProvider.DATA_KEY_STARTTIME, time);
                         }break;
            case 2: {
              int endTime = IOUtils.getIntForBytes(data);
              
              Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
              utc.setTimeInMillis(update.getDate());
              
              Calendar cal = Calendar.getInstance(update.getTimeZone());
              cal.set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH));
              cal.set(Calendar.MONTH, utc.get(Calendar.MONTH));
              cal.set(Calendar.YEAR, utc.get(Calendar.YEAR));
              
              cal.set(Calendar.HOUR_OF_DAY, endTime / 60);
              cal.set(Calendar.MINUTE, endTime % 60);
              cal.set(Calendar.SECOND, 30);
              
              Long o = values.getAsLong(TvBrowserContentProvider.DATA_KEY_STARTTIME);
              
              if(o instanceof Long) {
                if(o > cal.getTimeInMillis()) {
                  cal.add(Calendar.DAY_OF_YEAR, 1);
                }
              }
              
              long time =  (((long)(cal.getTimeInMillis() / 60000)) * 60000);
              
              values.put(TvBrowserContentProvider.DATA_KEY_ENDTIME, time);
           }break;
            case 3: values.put(TvBrowserContentProvider.DATA_KEY_TITLE, new String(data));break;
            case 4: values.put(TvBrowserContentProvider.DATA_KEY_TITLE_ORIGINAL, new String(data));break;
            case 5: values.put(TvBrowserContentProvider.DATA_KEY_EPISODE_TITLE, new String(data));break;
            case 6: values.put(TvBrowserContentProvider.DATA_KEY_EPISODE_TITLE_ORIGINAL, new String(data));break;
            case 7: values.put(TvBrowserContentProvider.DATA_KEY_SHORT_DESCRIPTION, new String(data));break;
            case 8: values.put(TvBrowserContentProvider.DATA_KEY_DESCRIPTION, new String(data));break;
            case 0xA: values.put(TvBrowserContentProvider.DATA_KEY_ACTORS, new String(data));break;
            case 0xB: values.put(TvBrowserContentProvider.DATA_KEY_REGIE, new String(data));break;
            case 0xC: values.put(TvBrowserContentProvider.DATA_KEY_CUSTOM_INFO, new String(data));break;
            case 0xD: values.put(TvBrowserContentProvider.DATA_KEY_CATEGORIES, IOUtils.getIntForBytes(data));break;
            case 0xE: values.put(TvBrowserContentProvider.DATA_KEY_AGE_LIMIT, IOUtils.getIntForBytes(data));break;
            case 0xF: values.put(TvBrowserContentProvider.DATA_KEY_WEBSITE_LINK, new String(data));break;
            case 0x10: values.put(TvBrowserContentProvider.DATA_KEY_GENRE, new String(data));break;
            case 0x11: values.put(TvBrowserContentProvider.DATA_KEY_ORIGIN, new String(data));break;
            case 0x12: values.put(TvBrowserContentProvider.DATA_KEY_NETTO_PLAY_TIME, IOUtils.getIntForBytes(data));break;
            case 0x13: values.put(TvBrowserContentProvider.DATA_KEY_VPS, IOUtils.getIntForBytes(data));break;
            case 0x14: values.put(TvBrowserContentProvider.DATA_KEY_SCRIPT, new String(data));break;
            case 0x15: values.put(TvBrowserContentProvider.DATA_KEY_REPETITION_FROM, new String(data));break;
            case 0x16: values.put(TvBrowserContentProvider.DATA_KEY_MUSIC, new String(data));break;
            case 0x17: values.put(TvBrowserContentProvider.DATA_KEY_MODERATION, new String(data));break;
            case 0x18: values.put(TvBrowserContentProvider.DATA_KEY_YEAR, IOUtils.getIntForBytes(data));break;
            case 0x19: values.put(TvBrowserContentProvider.DATA_KEY_REPETITION_ON, new String(data));break;
            case 0x1A: values.put(TvBrowserContentProvider.DATA_KEY_PICTURE, data);break;
            case 0x1B: values.put(TvBrowserContentProvider.DATA_KEY_PICTURE_COPYRIGHT, new String(data));break;
            case 0x1C: values.put(TvBrowserContentProvider.DATA_KEY_PICTURE_DESCRIPTION, new String(data));break;
            case 0x1D: values.put(TvBrowserContentProvider.DATA_KEY_EPISODE_NUMBER, IOUtils.getIntForBytes(data));break;
            case 0x1E: values.put(TvBrowserContentProvider.DATA_KEY_EPISODE_COUNT, IOUtils.getIntForBytes(data));break;
            case 0x1F: values.put(TvBrowserContentProvider.DATA_KEY_SEASON_NUMBER, IOUtils.getIntForBytes(data));break;
            case 0x20: values.put(TvBrowserContentProvider.DATA_KEY_PRODUCER, new String(data));break;
            case 0x21: values.put(TvBrowserContentProvider.DATA_KEY_CAMERA, new String(data));break;
            case 0x22: values.put(TvBrowserContentProvider.DATA_KEY_CUT, new String(data));break;
            case 0x23: values.put(TvBrowserContentProvider.DATA_KEY_OTHER_PERSONS, new String(data));break;
            case 0x24: values.put(TvBrowserContentProvider.DATA_KEY_RATING, IOUtils.getIntForBytes(data));break;
            case 0x25: values.put(TvBrowserContentProvider.DATA_KEY_PRODUCTION_FIRM, new String(data));break;
            case 0x26: values.put(TvBrowserContentProvider.DATA_KEY_AGE_LIMIT_STRING, new String(data));break;
            case 0x27: values.put(TvBrowserContentProvider.DATA_KEY_LAST_PRODUCTION_YEAR, IOUtils.getIntForBytes(data));break;
            case 0x28: values.put(TvBrowserContentProvider.DATA_KEY_ADDITIONAL_INFO, new String(data));break;
            case 0x29: values.put(TvBrowserContentProvider.DATA_KEY_SERIES, new String(data));break;
          }
          
          data = null;
        }
        
        if(values.get(TvBrowserContentProvider.DATA_KEY_ENDTIME) == null) {
          values.put(TvBrowserContentProvider.DATA_KEY_ENDTIME, 0);
        }
        
        Cursor test = getContentResolver().query(TvBrowserContentProvider.CONTENT_URI_DATA_UPDATE, null, where, null, null);
        
        if(test.getCount() > 0) {
          // program known update it
          getContentResolver().update(TvBrowserContentProvider.CONTENT_URI_DATA_UPDATE, values, where, null);
        }
        else {
          long startTime = values.getAsLong(TvBrowserContentProvider.DATA_KEY_STARTTIME);
          
          Calendar cal = Calendar.getInstance();
          cal.setTimeInMillis(startTime);
          
          if(cal.get(Calendar.HOUR_OF_DAY) >= getDayStart() && (cal.get(Calendar.HOUR_OF_DAY) < getDayEnd() || (cal.get(Calendar.HOUR_OF_DAY) == getDayEnd() && cal.get(Calendar.MINUTE) == 0))) {
            getContentResolver().insert(TvBrowserContentProvider.CONTENT_URI_DATA_UPDATE, values);            
          }
        }
        
        values.clear();
        values = null;
        
        test.close();
      }
      
      updateVersionTable(update,dataVersion);
      
      StringBuilder where = new StringBuilder();
      
      for(Integer id : missingFrameIDs) {
        if(id.intValue() > maxFrameID) {
          break;
        }
        else {
          if(where.length() > 0) {
            where.append(" OR ");
          }
          else {
            where.append(" ( ");
          }
          
          where.append(" ( ");
          where.append(TvBrowserContentProvider.DATA_KEY_DATE_PROG_ID);
          where.append(" = ");
          where.append(id);
          where.append(" ) ");
        }
      }
      
      if(where.length() > 0) {
        where.append(" ) AND ");
        where.append(" ( ");
        where.append(TvBrowserContentProvider.DATA_KEY_UNIX_DATE);
        where.append(" = ");
        where.append(update.getDate());
        where.append(" ) AND ( ");
        where.append(TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID);
        where.append(" = ");
        where.append(update.getChannelID());
        where.append(" ) ");
        
        Log.d(TAG, " Delete where clause " + where);
        
        int count = getContentResolver().delete(TvBrowserContentProvider.CONTENT_URI_DATA_UPDATE, where.toString(), null);
        Log.d(TAG, " Number of deleted programs " + count + " " + update.getUrl());
      }
      
      in.close();
    } catch (Exception e) {
      // TODO Auto-generated catch block
      Log.d("info", "UPDATE_DATA", e);
    }
    
    dataFile.delete();
  }
  
  private void updateVersionTable(ChannelUpdate update, int dataVersion) {
    long daysSince1970 = update.getDate() / 24 / 60 / 60000;
    
    ContentValues values = new ContentValues();
    
    values.put(TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID, update.getChannelID());
    values.put(TvBrowserContentProvider.VERSION_KEY_DAYS_SINCE_1970, daysSince1970);
    
    if(update.getUrl().toLowerCase().contains(SettingConstants.LEVEL_NAMES[0])) {
      values.put(TvBrowserContentProvider.VERSION_KEY_BASE_VERSION,dataVersion);
    }
    
    String where = TvBrowserContentProvider.CHANNEL_KEY_CHANNEL_ID + " = " + update.getChannelID() + " AND " + TvBrowserContentProvider.VERSION_KEY_DAYS_SINCE_1970 + " = " + daysSince1970;
    
    Log.d("DOWN", daysSince1970 + " " + update.getUrl() + " " + dataVersion);
    Log.d(TAG, " Version update where: "+where);
    Log.d(TAG, " Version update content count "+values.size());
    
    Cursor test = getContentResolver().query(TvBrowserContentProvider.CONTENT_URI_DATA_VERSION, null, where, null, null);
    
    // update current value
    if(test.getCount() > 0) {
      test.moveToFirst();
      long id = test.getLong(test.getColumnIndex(TvBrowserContentProvider.KEY_ID));
      
      int count = getContentResolver().update(ContentUris.withAppendedId(TvBrowserContentProvider.CONTENT_URI_DATA_VERSION, id), values, null, null);
      Log.d(TAG, " Number of updated versions " + count);
    }
    else {
      Uri inserted = getContentResolver().insert(TvBrowserContentProvider.CONTENT_URI_DATA_VERSION, values);
      Log.d(TAG, " Inserted version " + inserted);
    }
    
    test.close();
  }
  
  /**
   * Helper class for data update.
   * Stores url, channel ID, timezone and date of a channel.
   * 
   * @author René Mach
   */
  private class ChannelUpdate {
    private String mUrl;
    private long mChannelID;
    private String mTimeZone;
    private long mDate;
    
    public ChannelUpdate(String url, long channelID, String timezone, long date) {
      mUrl = url;
      mChannelID = channelID;
      mTimeZone = timezone;
      mDate = date;
    }
    
    public String getUrl() {
      return mUrl;
    }
    
    public long getChannelID() {
      return mChannelID;
    }
    
    public TimeZone getTimeZone() {
      return TimeZone.getTimeZone(mTimeZone);
    }
    
    public long getDate() {
      return mDate;
    }
  }
  
  /**
   * Class that stores informations about available data for a channel on an update server.
   * <p>
   * @author René Mach
   */
  private static class ChannelFrame {
    private String mCountry;
    private String mChannelID;
    private int mDayCount;
    
    private HashMap<Integer,int[]> mLevelVersions;
    
    public ChannelFrame(String country, String channelID, int dayCount) {
      mCountry = country;
      mChannelID = channelID;
      mDayCount = dayCount;
      
      mLevelVersions = new HashMap<Integer, int[]>();
    }
    
    public void add(int day, int[] levelVersions) {
      mLevelVersions.put(day, levelVersions);
    }
    
    public int[] getVersionForDay(int day) {
      return mLevelVersions.get(Integer.valueOf(day));
    }
    
    public int getDayCount() {
      return mDayCount;
    }
    
    public String getCountry() {
      return mCountry;
    }
    
    public String getChannelID() {
      return mChannelID;
    }
  }
  
  /**
   * Helper class that stores informations about the available data
   * on an update server.
   * 
   * @author René Mach
   */
  private static class Summary {
    private int mVersion;
    private long mStartDaySince1970;
    private int mLevels;

    /**
     * List with available ChannelFrames for the server.
     */
    private ArrayList<ChannelFrame> mFrameList;
    
    public Summary() {
      mFrameList = new ArrayList<ChannelFrame>();
    }
    
    public void setVersion(int version) {
      mVersion = version;
    }
    
    public void setStartDaySince1970(long days) {
      mStartDaySince1970 = days-1;
    }
    
    public void setLevels(int levels) {
      mLevels = levels;
    }
    
    public void addChannelFrame(ChannelFrame frame) {
      mFrameList.add(frame);
    }
    
    public ChannelFrame[] getChannelFrames() {
      return mFrameList.toArray(new ChannelFrame[mFrameList.size()]);
    }
    
    public int getLevels() {
      return mLevels;
    }
    
    public long getStartDaySince1970() {
      return mStartDaySince1970;
    }
    
    public Calendar getStartDate() {
      Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      
      // calculate the number of miliseconds since 1970 to get to the UNIX time  
      cal.setTimeInMillis(mStartDaySince1970 * 24 * 60 * 60000);
      
      return cal;
    }
    
    /**
     * Get the ChannelFrame for the given channel ID
     * <p>
     * @param channelID The channel ID to get the ChannelFrame for.
     * @return The requested ChannelFrame or <code>null</code> if there is no ChannelFrame for given ID.
     */
    public ChannelFrame getChannelFrame(String channelID) {
     // Log.d(TAG, "CHANNELID " + channelID + " " +mFrameList.size());
      for(ChannelFrame frame : mFrameList) {
      //  Log.d(TAG, " FRAME ID " + frame.mChannelID);
        if(frame.mChannelID.equals(channelID)) {
          return frame;
        }
      }
      
      return null;
    }
  }
}