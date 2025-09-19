package info.altimeter.variometer;

import android.app.Application;
import android.content.Context;

import java.io.File;

public class BaseApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Context context = getApplicationContext();
        File dexOutputDir = context.getCodeCacheDir();
        dexOutputDir.setReadOnly();
    }
}
