package org.example.airhockey;

import java.util.Random;

import android.content.res.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.SimpleCursorAdapter;
import android.content.*;

import android.database.Cursor;
import java.util.Random;


public class AirHockeyView extends SurfaceView implements SurfaceHolder.Callback,Runnable {
	private static final String TAG = "AirHockey";

	private SurfaceHolder holder;//サーフェイスホルダー
	private Thread        thread;//スレッド
	private Context ctxt;

	private Random rnd;
	//Bitmap white, pink;
	Bitmap mallet, puck;

	boolean dragging, moved, goaled;
	int w, h;
	private int gw = 144;
	
	private int score_user, score_android;

	private float bw = 5;
	float puck_x, puck_y, puck_r, puck_vx, puck_vy;
	float mallet_x, mallet_y, mallet_r;
	float android_x, android_y, android_vx, android_vy;
	float mallet_ofs_x, mallet_ofs_y;

	long last_millis; 

	double hypot(double a, double b) {
		return Math.sqrt(a*a + b*b);
	}

	private void reset() {
		puck_x = w/2;
		puck_y = h - 144;
		puck_vx = puck_vy = 0;
		
		mallet_x = w/2;
		mallet_y = h - 40;

		android_x = w/2;
		android_y = 40;
		android_vx = 100; android_vy = 0;

		dragging = moved = goaled = false;
	}
	
	public AirHockeyView(Context context) {
		super(context);
		ctxt = context;

		//画像の読み込み
		Resources r = getResources();

		w = 320; // getWidth();
		h = 480 - 24; // getHeight();

		mallet = BitmapFactory.decodeResource(r,R.drawable.mallet42);  
		mallet_r = mallet.getWidth() / 2;
		puck = BitmapFactory.decodeResource(r,R.drawable.puck42);
		puck_r = puck.getWidth() / 2;

		reset();
		
		score_user = score_android = 0;

		// サーフェイスホルダーの生成
		holder = getHolder();
		holder.addCallback(this);
		//		holder.setFixedSize((int)w, (int)h);
		holder.setFixedSize(getWidth(),getHeight());
		Log.d(TAG, "getWidth() = "+ getWidth() +", getHeight() = "+ getHeight());

		last_millis = SystemClock.elapsedRealtime();
	}

	private void vibrate() {
		Vibrator v = (Vibrator)ctxt.getSystemService(Context.VIBRATOR_SERVICE); 
		v.vibrate(20);
	}

	private double crossAtT(double ax0, double ay0, double ax1, double ay1, double ar,
			double bx0, double by0, double bx1, double by1, double br) {
		return crossAtT(ax0, ay0, ax1 + (bx1 - bx0), ay1 + (by1 - by0), ar,
				bx0, by0, br);
	}
	private double crossAtT(double ax0, double ay0, double ax1, double ay1, double ar,
			double bx, double by, double br) {
		// Log.d(TAG, "crossAtT("+x0+","+y0+","+x1+","+y1+")");
		double dx = ax1 - ax0, dy = ay1 - ay0;
		/* X: mallet_x + (new_mallet_x - mallet_x)*t
		 * Y: mallet_y + (new_mallet_y - mallet_y)*t
		 * t: [0, 1]
		 * 
		 * (X - puck_x)^2 + (Y - puck_y)^2 = puck_r^2
		 * X^2 - 2.X.puck_x + puck_x^2 + Y^2 - 2.Y.puck_y + puck_y^2 - puck_r^2 = 0
		 * 
		 * (mx + dx.t)^2 - 2.px.(mx + dx.t) + px^2 + (my + dy.t)^2 - 2.py.(my + dy.t) + py^2 - pr^2 = 0
		 * (dx^2 + dy^2)t^2 - 2(px.dx + py.dy)t + {(mx - px)^2 + (my - py)^2 - pr^2} = 0
		 * 
		 * mx2 + 2mxdxt + dx2t2 - 2pxmx - 2pxdxt + px2
		 * + my2 + 2mydyt + dy2t2 - 2pymy - 2pydyt + py2 - prpr
		 * (mx2 - 2pxmx + px2) + (my2 - 2pymy + py2) - pr2 + (2mxdx + 2mydy - 2pxdx - 2pydy)t + (dx2 + dy2)t2
		 * = {(mx - px)^2 + (my - py)^2 - pr^2} + {2(mx - px)dx + 2(my - py)dy}t + (dx^2 + dy^2)t^2
		 */
		double a = dx*dx + dy*dy,
		b = 2 *( (ax0 - bx)*dx + (ay0 - by)*dy ),
		c = (ax0 - bx)*(ax0 - bx) + (ay0 - by)*(ay0 - by) - (ar + br)*(ar + br);
		/*
		 * -b += √(b^2 - 4ac)
		 * --
		 * 2a
		 */
		double D = b*b - 4*a*c;
		if (D < 0) {
			// no hit
			// Log.d(TAG, "... no hit.");
			return -1;
		} else if (D == 0) {
			// one possible hit
			double t = -b / a / 2;
			if (0.0 <= t && t <= 1.0) {
				Log.d(TAG, "... hit: t="+ t);
				return t;
			}
		} else {
			// two possible hits
			double d = (float)Math.sqrt((double)D);
			double t1 = (-b - d) / a / 2,
			t2 = (-b + d) / a / 2;
			if (0.0 <= t1 && t1 <= 1.0) {
				Log.d(TAG, "... hit: t1="+ t1);
				return t1;
			} else if (0.0 <= t2 && t2 <= 1.0) {
				Log.d(TAG, "... hit: t2="+ t2);
				return t2;
			}
		}
		// Log.d(TAG, "... no hit");
		return -1;
	}

	private boolean move(float x, float y) {
		double max_mallet_x = x - mallet_ofs_x,
		max_mallet_y = y - mallet_ofs_y;
		long millis = SystemClock.elapsedRealtime();
		double delta_t = 0.001 * (millis - last_millis);
		last_millis = millis;
		double max_puck_x = puck_x + puck_vx * delta_t,
		       max_puck_y = puck_y + puck_vy * delta_t;
		Log.d(TAG, "[B] delta_t = "+ delta_t);

		double t = crossAtT(mallet_x, mallet_y, max_mallet_x, max_mallet_y, mallet_r,
				puck_x, puck_y, max_puck_x, max_puck_y, puck_r);
		moved = true;
		if (t < 1e-3) { // <0
			// no cross
			mallet_x = (float)max_mallet_x;
			mallet_y = (float)max_mallet_y;
			puck_x = (float)max_puck_x;
			puck_y = (float)max_puck_y;
			
			return true;
		} else {
			// crossed
			double mallet_dx = (max_mallet_x - mallet_x)*(1.0 - t)/delta_t,
			       mallet_dy = (max_mallet_y - mallet_y)*(1.0 - t)/delta_t;

			puck_x = (float)(puck_x + (max_puck_x - puck_x)*t);
			puck_y = (float)(puck_y + (max_puck_y - puck_y)*t);
			mallet_x = (float)(mallet_x + (max_mallet_x - mallet_x)*t);
			mallet_y = (float)(mallet_y + (max_mallet_y - mallet_y)*t);

			/*
			puck_x = (float)(puck_x + (puck_vx - puck_x)*t);
			puck_y = (float)(puck_y + (puck_vy - puck_y)*t);
			puck_vx = -puck_vx; puck_vy = -puck_vy; // これは嘘
			 */

			double in = Math.atan2(-puck_vy, -puck_vx);
			double m = Math.atan2(puck_y - mallet_y, puck_x - mallet_x);
			double ir = hypot(puck_vx, puck_vy);
			double out = m*2 - in; // out - m = m - in

			puck_vx = (float)(ir * Math.cos(out) + mallet_dx/2);
			puck_vy = (float)(ir * Math.sin(out) + mallet_dy/2);

			Log.d(TAG, "HIT, t="+t);
			vibrate();
			return false;
		}
	}


	@Override
	public boolean onTouchEvent(MotionEvent evt) {
		//if (event.getAction() != MotionEvent.ACTION_DOWN)
		//	return super.onTouchEvent(event);
		///Log.d(TAG, "onTouchEvent " + x + "," + y);

		float x, y;

		switch (evt.getAction()) {		
		case MotionEvent.ACTION_DOWN:
			x = evt.getX();
			y = evt.getY();
			Log.d(TAG, "ACTION_DOWN " + x + "," + y);
			if (hypot(x - mallet_x, y - mallet_y) <= 80) { // mallet_r*2){
				mallet_ofs_x = x - mallet_x;
				mallet_ofs_y = y - mallet_y;
				//last_mallet_x = mallet_x;
				//last_mallet_y = mallet_y;
				dragging = true;
				Log.d(TAG, "  start dragging...");
			}
			return true;

		case MotionEvent.ACTION_MOVE:
			if (!dragging) break;

			Log.d(TAG, "ACTION_MOVE");
			for (int i=0, n=evt.getHistorySize(); i<n; i++) {
				if (dragging) {
					x = evt.getHistoricalX(i);
					y = evt.getHistoricalY(i);
					Log.d(TAG, "  "+ i +") "+ x + ", " + y);
					if (y < h/2+mallet_r || !move(x, y)) dragging = false;
				}
				if (!dragging) break;
			}

			if (dragging) {
				x = evt.getX();
				y = evt.getY();
				Log.d(TAG, "  *) "+ x + ", " + y);
				if (y < h/2+mallet_r || !move(x, y)) dragging = false;
			}

			return true;

		case MotionEvent.ACTION_UP:
			if (!dragging) break;

			x = evt.getX();
			y = evt.getY();
			Log.d(TAG, "ACTION_UP " + x + "," + y);
			if (y < h/2+mallet_r || !move(x, y)) dragging = false;

			return true;
		default:
			break;
		}
		return super.onTouchEvent(evt);
	}

	//サーフェイスの生成
	public void surfaceCreated(SurfaceHolder holder) {
		thread=new Thread(this);
		thread.start();

		rnd = new Random();
	}

	//サーフェイスの終了
	public void surfaceDestroyed(SurfaceHolder holder) { thread=null; }
	//サーフェイスの変更
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }

	//スレッドの処理
	public void run() {                      
		Canvas canvas;
		while(thread!=null) {
			//ロック
			canvas=holder.lockCanvas();

			//描画
			canvas.drawColor(0xFF666666); // Color.WHITE);

			Paint paint = new Paint();
			paint.setAntiAlias(true);
			paint.setTextSize(10);
			paint.setColor(0xFFFFFFFF);
			//paint.setFlags(flags)
			paint.setStyle(Paint.Style.STROKE);

			Paint bigletter = new Paint();
			bigletter.setAntiAlias(true);
			bigletter.setTextSize(64);
			bigletter.setColor(0xFFFFFFFF);

			long millis = SystemClock.elapsedRealtime();
			double delta_t = 0.001 * (millis - last_millis);
			last_millis = millis;
			if (!moved) {
				double max_puck_x = puck_x + puck_vx * delta_t,
				       max_puck_y = puck_y + puck_vy * delta_t;
				//Log.d(TAG, "[C] Δt = "+ 0.001 * (millis - last_millis));

				double t = crossAtT(puck_x, puck_y, max_puck_x, max_puck_y, puck_r,
						mallet_x, mallet_y, mallet_r);
				if (t < 1e-3) { // <0
					// no cross
					puck_x = (float)max_puck_x;
					puck_y = (float)max_puck_y;
				} else {
					// crossed
					double in = Math.atan2(-puck_vy, -puck_vx);
					double ir = hypot(puck_vx, puck_vy);
					double m = Math.atan2(puck_y - mallet_y, puck_x - mallet_x);
					double out = m*2 - in; // out - m = m - in

					puck_x = (float)(puck_x + (max_puck_x - (double)puck_x)*t);
					puck_y = (float)(puck_y + (max_puck_y - (double)puck_y)*t);

					puck_vx = (float)(ir * Math.cos(out));
					puck_vy = (float)(ir * Math.sin(out));
					// puck_vx = -puck_vx; puck_vy = -puck_vy; // これは嘘
					Log.d(TAG, "HIT, t="+t);
					vibrate();
				}
			}
			
			// android!
			if (puck_y < h/2) {
				double max_android_x = android_x + android_vx * delta_t,
				       max_android_y = android_y + android_vy * delta_t;
				double max_puck_x = puck_x + puck_vx * delta_t,
			           max_puck_y = puck_y + puck_vy * delta_t;
			
				double t = crossAtT(puck_x, puck_y, max_puck_x, max_puck_y, puck_r,
						            android_x, android_y, max_android_x, max_android_y, mallet_r);
				if (t > 0) {
					double ax = android_x + (max_android_x - android_x)*t,
						   ay = android_y + (max_android_y - android_y)*t,
						   px = puck_x + (max_puck_x - puck_x)*t,
						   py = puck_y + (max_puck_y - puck_y)*t;
					double mid_x = (ax + px)/2, mid_y = (ay + py)/2;
					double theta = Math.atan2(py - ay, px - ax);
					puck_x = (float)(mid_x + puck_r * Math.cos(theta)); 
					puck_y = (float)(mid_y + puck_r * Math.sin(theta));
					
					double in = Math.atan2(-puck_vy, -puck_vx);
					double m = Math.atan2(puck_y - android_y, puck_x - android_x);
					double ir = hypot(puck_vx, puck_vy);
					double out = m*2 - in; // out - m = m - in

					puck_vx = (float)(ir * Math.cos(out));;
					puck_vy = (float)(ir * Math.sin(out));
				}
				android_x = (float)max_android_x;
				android_y = (float)max_android_y;
				
				double aw = bw + mallet_r*2;
				if (android_x < aw) {
					android_x = (float)(aw + (aw - android_x));
					android_vx = -android_vx;
				} else if (android_x > w - aw) {
					android_x = (float)((w - aw) - (android_x - (w - aw)));
					android_vx = -android_vx;
				}
				if (android_y < aw) {
					android_y = (float)(aw + (aw - android_y));
					android_vy = -android_vy;
				} else if (android_y > h - aw) {
					android_y = (float)((h - aw) - (android_y - (h - aw)));
					android_vy = -android_vy;
				}
				
				if (goaled) android_vx = android_vy = 0;
			}

			// 補正
			if (hypot(mallet_x - puck_x, mallet_y - puck_y) < mallet_r + puck_r) {
				double theta = Math.atan2(mallet_y - puck_y, mallet_x - puck_x);
				double mid_x = (mallet_x + puck_x)/2, mid_y = (mallet_y + puck_y)/2;
				mallet_x = (float)(mid_x + (mallet_r * 1.001) * Math.cos(theta));
				mallet_y = (float)(mid_y + (mallet_r * 1.001) * Math.sin(theta));
				puck_x = (float)(mid_x - (puck_r * 1.001) * Math.cos(theta));
				puck_y = (float)(mid_y - (puck_r * 1.001) * Math.sin(theta));
			}

			float bwr = bw + puck_r;
			// side
			while (true) {
				if (puck_x <= bwr) {
					puck_x = bwr + (bwr - puck_x);
					puck_vx = -puck_vx;
				} else if (puck_x >= w - bwr) {
					puck_x = (w - bwr) - (puck_x - (w - bwr));
					puck_vx = -puck_vx;
				} else {
					break;
				}
			}
			// goal
			while (true) {
				if (puck_y <= bwr) {
					if (w/2-gw/2+puck_r < puck_x && puck_x < w/2+gw/2-puck_r) {
						Log.d(TAG,"GOAL THERE");
						goaled = true;
						score_user++;
						break;
					} else {
						puck_y = bwr + (bwr - puck_y);
						puck_vy = -puck_vy;
					}
				} else if (puck_y >= h - bwr) {
					if (w/2-gw/2+puck_r < puck_x && puck_x < w/2+gw/2-puck_r) {
						Log.d(TAG,"GOAL HERE");
						goaled = true;
						score_android++;
						break;
					} else {
						puck_y = (h - bwr) - (puck_y - (h - bwr));
						puck_vy = -puck_vy;
					}
				} else {
					break;
				}
			}

			if (!goaled) {
				canvas.drawBitmap(puck, puck_x - puck_r, puck_y - puck_r, null);
			}
			canvas.drawBitmap(mallet, mallet_x - mallet_r, mallet_y - mallet_r, null);
			canvas.drawBitmap(mallet, android_x - mallet_r, android_y - mallet_r, null);

			//canvas.drawText("o",0,1, px,py, paint);
			//px+=1; py+=1;

			//int w = this.getWidth(), h = this.getHeight();
			// canvas.drawLine(0,0, 100,100, paint);
			// たて
			canvas.drawLine(bw,bw, bw,h-bw, paint);
			canvas.drawLine(w-bw,bw, w-bw,h-bw, paint);
			// よこ
			canvas.drawLine(bw,bw, w/2-gw/2,bw, paint);
			canvas.drawCircle(w/2-gw/2+1,bw,2,paint);
			canvas.drawCircle(w/2+gw/2-1,bw,2,paint);
			canvas.drawLine(w/2+gw/2,bw, w-bw,bw, paint);
			
			canvas.drawLine(bw,h/2, w-bw,h/2, paint); // centre
			
			canvas.drawLine(bw,h-bw, w/2-gw/2,h-bw, paint);
			canvas.drawCircle(w/2-gw/2+1,h-bw,2,paint);
			canvas.drawCircle(w/2+gw/2-1,h-bw,2,paint);
			canvas.drawLine(w/2+gw/2,h-bw, w-bw,h-bw, paint);

			if (goaled) {
				String score_str = "" + score_user + "-" + score_android;
				
				float[] widths = new float[score_str.length()];
				int letters = bigletter.getTextWidths(score_str, widths);
				float width = 0;
				for(int i=0;i<letters;i++) width += widths[i];
				canvas.drawText(score_str, 0, score_str.length(), w/2-width/2,h/2-32, bigletter);
			}
			
			//アンロック
			holder.unlockCanvasAndPost(canvas);

			if (goaled) {
				try { Thread.sleep(1000); }
				catch (Exception e) { }
				reset();
			} else {
				//スリープ
				try { Thread.sleep(50); }
				catch (Exception e) { }
			}

			// Log.d(TAG, "v = "+ hypot(puck_vx, puck_vy));

			moved = false;
		}
	}	
}