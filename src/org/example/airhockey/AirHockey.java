package org.example.airhockey;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Window;

public class AirHockey extends Activity {
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//        setContentView(R.layout.main);
		
		requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFormat(PixelFormat.TRANSLUCENT);
        
		setContentView(new AirHockeyView(this));
		Log.d("AH", "onCreate");

		Vibrator v = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE); 
		long[] pattern = {3000, 1000, 2000, 5000, 3000, 1000}; // OFF/ON/OFF/ON...
		v.vibrate(pattern, -1);
		//v.vibrate(10);
	}

	/*
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_MOVE) {
			Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
			vibrator.vibrate(10);
		}
		return super.onTouchEvent(event);
	}
	*/
	
}