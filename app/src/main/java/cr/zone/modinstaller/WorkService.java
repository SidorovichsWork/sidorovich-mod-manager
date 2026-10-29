package cr.zone.modinstaller;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

public class WorkService extends Service {
    public interface Task { void run() throws Exception; }
    public static volatile boolean busy;
    public static volatile String status=Text.get("message_175"), error="";
    public static volatile int revision;
    private static Task pending;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    public static synchronized void launch(Context context,String label,Task task) {
        if(busy)throw new IllegalStateException(Text.get("message_176"));
        busy=true;error="";status=label;pending=task;
        try{context.startForegroundService(new Intent(context,WorkService.class));}catch(RuntimeException e){busy=false;pending=null;throw e;}
    }
    public static void progress(String text){status=text;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("operations",Text.get("message_177"),NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        startForeground(1,new Notification.Builder(this,"operations").setSmallIcon(cr.zone.modinstaller.R.drawable.ic_zone).setContentTitle(getString(R.string.app_name)).setContentText(Text.get("message_178")).setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Task task=pending;pending=null;
        if(task==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        executor.execute(()->{
            PowerManager.WakeLock lock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AnomalyMods:operation");lock.acquire(6*60*60*1000L);
            try{task.run();}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();status=Text.get("message_179");}
            finally{if(lock.isHeld())lock.release();busy=false;revision++;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
        });
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){executor.shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
