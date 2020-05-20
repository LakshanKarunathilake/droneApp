package com.example.locationapp;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.gson.Gson;

import org.greenrobot.eventbus.EventBus;

import java.net.URISyntaxException;
import java.util.Date;

public class BackgroundService extends Service {

    private static final String CHANNEL_ID = "channel1";
    private static final String EXTRA_STARTED_FROM_NOTIFICATION = "com.example.locationapp"+
            ".started_from_notification";

    private final IBinder mBinder = new LocalBinder();
    private static  final long UPDATE_INTERVAL_IN_MIL = 1000;
    private static final long FASTEST_UPDATE_INTERVAL_IN_MIL = UPDATE_INTERVAL_IN_MIL/2;
    private static  final int NOTI_ID = 1223;
    private boolean mChanagingConfiguration = false;
    private NotificationManager mNotificationMnager;

    private LocationRequest locationRequest;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private Handler mServiceHandler;
    private Location mLocation;

    public Socket mSocket;
    public Socket mSocket2;
    {
        try {
            mSocket = IO.socket("http://173.82.106.105/");
            mSocket2 = IO.socket("https://173.82.173.9/");
        } catch (URISyntaxException e) {}
    }


    public BackgroundService(){}

    @Override
    public void onCreate() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback(){
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                onNewLOcation(locationResult.getLastLocation());
            }
        };
        
        createLocationrequest();
        getLastLocation();

        HandlerThread handlerThread = new HandlerThread("ISHDev");
        handlerThread.start();
        mServiceHandler = new Handler((handlerThread.getLooper()));
        mNotificationMnager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name)
            ,NotificationManager.IMPORTANCE_DEFAULT);
            mNotificationMnager.createNotificationChannel(mChannel);
        }

        mSocket.connect();
        mSocket2.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false );

        if(startedFromNotification){
            removeLocationUpdates();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mChanagingConfiguration = true;
    }

    public void removeLocationUpdates() {
        try{
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
            Common.setRequestingLocationUpdates(this, false);
            stopSelf();
        } catch (SecurityException ex){
            Common.setRequestingLocationUpdates(this, true);
            Log.e("ISH_DEV", "Lost location permission. Could not remove updates. "+ex);
        }
    }

    private void getLastLocation() {
        try{
            fusedLocationProviderClient.getLastLocation()
                    .addOnCompleteListener(new OnCompleteListener<Location>() {
                        @Override
                        public void onComplete(@NonNull Task<Location> task) {
                            if(task.isSuccessful() && task.getResult() != null)
                                mLocation = task.getResult();
                            else
                                Log.e("ISH_DEV", "Failed to get location");
                        }
                    });
        } catch (SecurityException ex){
            Log.e("ISH_DEV", "Lost location permission. "+ex);
        }
    }

    private void createLocationrequest() {
        locationRequest = new LocationRequest();
        locationRequest.setInterval(UPDATE_INTERVAL_IN_MIL);
        locationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MIL);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    private void onNewLOcation(Location lastLocation) {
        mLocation = lastLocation;
        EventBus.getDefault().postSticky(new SendLocationToActivity(mLocation));

        //Update notification content if running as a foreground service
        if(servieIsRunningInForeGround(this)){
//            mNotificationMnager.notify(NOTI_ID, getNotification());
            Gson gson = new Gson();
            mSocket.emit("GPS_DATA",gson.toJson(new NewLocation(lastLocation.getLatitude(), lastLocation.getLongitude(),
                    lastLocation.getAltitude(), new Date(), lastLocation)));
            mSocket2.emit("GPS_DATA",gson.toJson(new NewLocation(lastLocation.getLatitude(), lastLocation.getLongitude(),
                    lastLocation.getAltitude(), new Date(), lastLocation)));
        }
    }

    private Notification getNotification() {
        Intent intent = new Intent(this, BackgroundService.class);
        String text = Common.getLocationText(mLocation);

        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);
        PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .addAction(R.drawable.ic_launch_black_24dp, "Launch", activityPendingIntent)
                .addAction(R.drawable.ic_cancel_black_24dp, "Remove", servicePendingIntent)
                .setContentText(text)
                .setContentTitle(Common.getLocationTitle(this))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setTicker(text)
                .setWhen(System.currentTimeMillis());

        //Set the channel id for Android O
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            builder.setChannelId(CHANNEL_ID);

        }

        return builder.build();
    }

    private boolean servieIsRunningInForeGround(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.RunningServiceInfo service:manager.getRunningServices(Integer.MAX_VALUE))
            if(getClass().getName().equals(service.service.getClassName()))
                if(service.foreground)
                    return true;
        return false;
    }

    public void requestLocationUpdates() {
        Common.setRequestingLocationUpdates(this, true);
        startService(new Intent(getApplicationContext(), BackgroundService.class));
        try{
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper());
        } catch (SecurityException ex){
            Log.e("ISH_DEV", "Lost location permission. Could not request it. "+ex);
        }
    }

    public class LocalBinder extends Binder{
        BackgroundService getService(){
            return BackgroundService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        stopForeground(true);
        mChanagingConfiguration = false;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        stopForeground(true);
        mChanagingConfiguration = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if(!mChanagingConfiguration && Common.requestingLocationUpdates(this))
            startForeground(NOTI_ID, getNotification());
        return true;
    }

    @Override
    public void onDestroy() {
        mServiceHandler.removeCallbacks(null);
        super.onDestroy();
    }
}
