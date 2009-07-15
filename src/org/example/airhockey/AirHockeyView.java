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

	private SurfaceHolder holder;
	private Thread        thread;
	private Context ctxt;

	private Random rnd;
	//Bitmap white, pink;
	Bitmap mallet, puck;

	boolean dragging, moved, goaled, goaled_by_android;
	int w, h;
	private int goal_width = 144;
	
	private int score_user, score_android;

	private final double max_speed = 750;
	private final int score_to_win = 10;
	
	private float bw = 5;
	float puck_x, puck_y, puck_r, puck_vx, puck_vy;
	float mallet_x, mallet_y, mallet_r;
	float android_x, android_y, android_vx, android_vy;
	float mallet_ofs_x, mallet_ofs_y;
	float android_home_x, android_home_y;

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

		android_x = android_home_x = w/2;
		android_y = android_home_y = 40;
		android_vx = 200; android_vy = 0;

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

	private void autoMoveAndroid(double delta_t) {
		android_x += android_vx * delta_t;
		android_y += android_vy * delta_t;

		double r1 = 6.0; // どのくらい迅速に追いつくか
		double r2 = 0.3; // 0に近づけば反応が悪く、1.0に近づけば反応がよい
		double dx = (puck_x - android_x)*r1;
		if (dx > max_speed) dx = max_speed;
		android_vx = (float)(android_vx*(1.0-r2) + dx*r2);
				
		/**
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
		**/
		float aw = bw + mallet_r*2;
		if (android_x < aw) {
			android_x = aw;
			android_vx = 0;
		} else if (android_x > w - aw) {
			android_x = w - aw;
			android_vx = 0;
		}
		if (android_y < aw) {
			android_y = aw;
			android_vy = 0;
		} else if (android_y > h - aw) {
			android_y = h - aw;
			android_vy = 0;
		}
	}
	
	// マレット移動
	private boolean moveMallet(float x, float y) {
		double max_mallet_x = x - mallet_ofs_x,
				max_mallet_y = y - mallet_ofs_y;
		long millis = SystemClock.elapsedRealtime();
		double delta_t = 0.001 * (millis - last_millis);
		last_millis = millis;
		double max_puck_x = puck_x + puck_vx * delta_t,
		       max_puck_y = puck_y + puck_vy * delta_t;
		double max_android_x = android_x + android_vx * delta_t,
	       		max_android_y = android_y + android_vy * delta_t;
		Log.d(TAG, "[B] delta_t = "+ delta_t);

		double t = crossAtT(mallet_x, mallet_y, max_mallet_x, max_mallet_y, mallet_r,
				puck_x, puck_y, max_puck_x, max_puck_y, puck_r);
		moved = true;
		
		boolean rv = false; // 返り値
		
		if (t < 1e-3) { // <0
			// no cross
			mallet_x = (float)max_mallet_x;
			mallet_y = (float)max_mallet_y;
			puck_x = (float)max_puck_x;
			puck_y = (float)max_puck_y;
//			android_x = (float)max_android_x;
//			android_y = (float)max_android_y;
			autoMoveAndroid(delta_t);
			rv = true;
		} else {
			// crossed
			double mallet_dx = (max_mallet_x - mallet_x)*(1.0 - t)/delta_t,
			       mallet_dy = (max_mallet_y - mallet_y)*(1.0 - t)/delta_t;

			puck_x = (float)(puck_x + (max_puck_x - puck_x)*t);
			puck_y = (float)(puck_y + (max_puck_y - puck_y)*t);
			mallet_x = (float)(mallet_x + (max_mallet_x - mallet_x)*t);
			mallet_y = (float)(mallet_y + (max_mallet_y - mallet_y)*t);
//			android_x = (float)(android_x + (max_android_x - android_x)*t);
//			android_y = (float)(android_y + (max_android_y - android_y)*t);
			autoMoveAndroid(delta_t*t);

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

			double puck_v = hypot(puck_vx,puck_vy);
			if (puck_v > max_speed) {
				puck_vx *= (max_speed / puck_v);
				puck_vy *= (max_speed / puck_v);
			}
			
			Log.d(TAG, "HIT, t="+t);
			vibrate();
		}
		
		// こちらに追随
		//android_x = (float)(android_home_x + (mallet_x - android_home_x)*0.666);
		//android_y = android_home_y;

		return rv;
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
					if (y < h/2+mallet_r || !moveMallet(x, y)) dragging = false;
				}
				if (!dragging) break;
			}

			if (dragging) {
				x = evt.getX();
				y = evt.getY();
				Log.d(TAG, "  *) "+ x + ", " + y);
				if (y < h/2+mallet_r || !moveMallet(x, y)) dragging = false;
			}

			return true;

		case MotionEvent.ACTION_UP:
			if (!dragging) break;

			x = evt.getX();
			y = evt.getY();
			Log.d(TAG, "ACTION_UP " + x + "," + y);
			if (y < h/2+mallet_r || !moveMallet(x, y)) dragging = false;

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

	private void awaySide(double delta_t) {
		double max_android_x = android_x + android_vx * delta_t,
				max_android_y = android_y + android_vy * delta_t;
		double max_puck_x = puck_x + puck_vx * delta_t,
				max_puck_y = puck_y + puck_vy * delta_t;

		double t = crossAtT(puck_x, puck_y, max_puck_x, max_puck_y, puck_r,
							android_x, android_y, max_android_x, max_android_y, mallet_r);
		if (t < 1e-3) { // <0
			// no cross
			puck_x = (float)max_puck_x;
			puck_y = (float)max_puck_y;
		} else {
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
//		android_x = (float)max_android_x;
//		android_y = (float)max_android_y;
		autoMoveAndroid(delta_t);

		// 補正
		if (hypot(android_x - puck_x, android_y - puck_y) < mallet_r + puck_r) {
			double theta = Math.atan2(android_y - puck_y, android_x - puck_x);
			double mid_x = (android_x + puck_x)/2, mid_y = (android_y + puck_y)/2;
			android_x = (float)(mid_x + (mallet_r * 1.001) * Math.cos(theta));
			android_y = (float)(mid_y + (mallet_r * 1.001) * Math.sin(theta));
			puck_x = (float)(mid_x - (puck_r * 1.001) * Math.cos(theta));
			puck_y = (float)(mid_y - (puck_r * 1.001) * Math.sin(theta));
		}

		if (goaled) android_vx = android_vy = 0; // stop android when goaled
	}
	
	private void homeSide(double delta_t) {
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

		autoMoveAndroid(delta_t);

		// 補正
		if (hypot(mallet_x - puck_x, mallet_y - puck_y) < mallet_r + puck_r) {
			double theta = Math.atan2(mallet_y - puck_y, mallet_x - puck_x);
			double mid_x = (mallet_x + puck_x)/2, mid_y = (mallet_y + puck_y)/2;
			mallet_x = (float)(mid_x + (mallet_r * 1.001) * Math.cos(theta));
			mallet_y = (float)(mid_y + (mallet_r * 1.001) * Math.sin(theta));
			puck_x = (float)(mid_x - (puck_r * 1.001) * Math.cos(theta));
			puck_y = (float)(mid_y - (puck_r * 1.001) * Math.sin(theta));
		}
	}
	
	private float getTextWidth(String txt, Paint p) {
		float[] widths = new float[txt.length()];
		int letters = p.getTextWidths(txt, widths);
		float width = 0;
		for(int i=0;i<letters;i++) width += widths[i];
		return width;
	}

	// パックの跳ね返り
	private void boundCheckOnWall() {
		float bwr = bw + puck_r;
		// サイドライン
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
		
		// ゴールライン
		while (true) {
			if (puck_y <= bwr) {
				if (w/2-goal_width/2+puck_r < puck_x && puck_x < w/2+goal_width/2-puck_r) {
					Log.d(TAG,"ANDROID!");
					goaled = true;
					goaled_by_android = true;
					score_user++;
					break;
				} else {
					puck_y = bwr + (bwr - puck_y);
					puck_vy = -puck_vy;
				}
			} else if (puck_y >= h - bwr) {
				if (w/2-goal_width/2+puck_r < puck_x && puck_x < w/2+goal_width/2-puck_r) {
					Log.d(TAG,"USER!");
					goaled = true;
					goaled_by_android = false;
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
	}
	
	//スレッドの処理
	public void run() {                      
		Canvas canvas;

		// Paintオブジェクトを準備
		Paint paint = new Paint();
		paint.setAntiAlias(true);
		paint.setTextSize(10);
		paint.setColor(0xFFFFFFFF);
		//paint.setFlags(flags)
		paint.setStyle(Paint.Style.STROKE);

		Paint fillpaint = new Paint();
	//	fillpaint.setAntiAlias(true);
	//	fillpaint.setTextSize(10);
		fillpaint.setColor(0xFF00FFFF);
		fillpaint.setStyle(Paint.Style.FILL);

		Paint bigletter = new Paint();
		bigletter.setAntiAlias(true);
		bigletter.setTextSize(64);
		bigletter.setColor(0xFFFFFFFF);

		while (thread != null) {
			//ロック
			canvas = holder.lockCanvas();

			//描画
			canvas.drawColor(0xFF666666); // Color.WHITE);

			// 前回からの経過時間(Δt)
			long millis = SystemClock.elapsedRealtime();
			double delta_t = 0.001 * (millis - last_millis);
			last_millis = millis;
			
			// Android側のヒット判定
			if (!moved) {
				if (puck_y < h/2)
					awaySide(delta_t);
				else
					homeSide(delta_t);

				double puck_v = hypot(puck_vx,puck_vy);
				if (puck_v > max_speed) {
					puck_vx *= (max_speed / puck_v);
					puck_vy *= (max_speed / puck_v);
				}
			}
			
			
			// 壁に当たったら跳ね返る
			boundCheckOnWall();

			if (!goaled) canvas.drawBitmap(puck, puck_x - puck_r, puck_y - puck_r, null);
			canvas.drawBitmap(mallet, mallet_x - mallet_r, mallet_y - mallet_r, null);
			canvas.drawBitmap(mallet, android_x - mallet_r, android_y - mallet_r, null);

			// サイドライン
			canvas.drawLine(bw,bw, bw,h-bw, paint);
			canvas.drawLine(w-bw,bw, w-bw,h-bw, paint);

			// ゴールライン（away side）
			canvas.drawLine(bw,bw, w/2-goal_width/2,bw, paint);
			if (goaled && goaled_by_android) {
				canvas.drawCircle(w/2-goal_width/2+1,bw,2,fillpaint);
				canvas.drawCircle(w/2+goal_width/2-1,bw,2,fillpaint);
			}
			canvas.drawCircle(w/2-goal_width/2+1,bw,2,paint);
			canvas.drawCircle(w/2+goal_width/2-1,bw,2,paint);
			canvas.drawLine(w/2+goal_width/2,bw, w-bw,bw, paint);
			
			// センターライン
			canvas.drawLine(bw,h/2, w-bw,h/2, paint);
			
			// ゴールライン（home side）
			canvas.drawLine(bw,h-bw, w/2-goal_width/2,h-bw, paint);
			if (goaled && !goaled_by_android) {
				canvas.drawCircle(w/2-goal_width/2+1,h-bw,2,fillpaint);
				canvas.drawCircle(w/2+goal_width/2-1,h-bw,2,fillpaint);
			}
			canvas.drawCircle(w/2-goal_width/2+1,h-bw,2,paint);
			canvas.drawCircle(w/2+goal_width/2-1,h-bw,2,paint);
			canvas.drawLine(w/2+goal_width/2,h-bw, w-bw,h-bw, paint);

			if (goaled) {
				String score_str = "" + score_user + "-" + score_android;
				float width = getTextWidth(score_str, bigletter);
				canvas.drawText(score_str, 0, score_str.length(), w/2-width/2,h/2-32, bigletter);
				
				if (score_user == score_to_win || score_android == score_to_win) {
					final String msg = (score_user == score_to_win) ? "YOU WIN!!" : "ANDROID WINS!!";
					
					Paint letter = new Paint();
					letter.setAntiAlias(true);
					letter.setTextSize(32);
					letter.setColor(0xFFFFFF00);
					float msg_width = getTextWidth(msg, letter);
					canvas.drawText(msg, 0, msg.length(), w/2-msg_width/2,h/2+32, letter);
				}
			}
			
			//アンロック
			holder.unlockCanvasAndPost(canvas);

			if (goaled) {
				if (score_user == score_to_win || score_android == score_to_win) {
					score_user = score_android = 0;
					try { Thread.sleep(3500); }
					catch (Exception e) { }
				} else {
					try { Thread.sleep(1000); }
					catch (Exception e) { }
				}
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