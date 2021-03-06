package com.interactive.stroke.draw;



import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.*;

import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Toast;

import com.interactive.stroke.draw.gestures.TranslationGestureDetector;
import com.interactive.stroke.draw.gestures.RotationGestureDetector;
import com.interactive.stroke.draw.utils.PreferenceConstants;

public class DrawActivity extends Activity implements OnSeekBarChangeListener{
    
    final static int LOAD_IMAGE = 1000;
    private static final String TAG = "Draw";
    private static final boolean DEBUG = false;
    private static final long NS_PER_MS = 1000000;
    private float DENSITY;

    private SharedPreferences mPrefs;
    
    public MasterBucket mMasterBucket;
    private ColorDialogFragment mColorDialog;
    public SlateFragment mSlateFragment;
    private SeekBar mBrushSizeBar;
    private SeekBar mBrushTransparencyBar;
    private ImageButton mBrushButton;
    private ImageButton mEraserButton;
    private boolean mIsPenUsed = true;
     
    private float mScaleFactor = 1.f;
    private float mRotationDegrees = 0.f;
    private float mTranslationX = 0.f;
    private float mTranslationY = 0.f;

    private ScaleGestureDetector mScaleDetector;
    private RotationGestureDetector mRotateDetector;
    private TranslationGestureDetector mTranslationDetector;
    private boolean mIsAGestureOngoing = false;
    private long lastCapturedEventTime = 0L;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        DENSITY = getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.format = PixelFormat.RGBA_8888;
        getWindow().setAttributes(lp);
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        setContentView(R.layout.main);


        mMasterBucket = (MasterBucket) findViewById(R.id.masterBucket);

        FragmentManager fragmentManager = getFragmentManager();
        mSlateFragment = (SlateFragment)  fragmentManager.findFragmentById(R.id.slate_fragment);

        ActionBar actionBar = this.getActionBar();
        View mActionBarView = getLayoutInflater().inflate(R.layout.action_bar_cv, null);
        actionBar.setCustomView(mActionBarView);
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM
            | ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_HOME_AS_UP);
        actionBar.setTitle(this.getString(R.string.draw_activity_title));

        mBrushSizeBar = (SeekBar) findViewById(R.id.brush_size_bar);
        int startBrushSliderPosition = 10;
        if (icicle != null) {
            startBrushSliderPosition = icicle.getInt(PreferenceConstants.PREF_BRUSH_SIZE);
        }
        mBrushSizeBar.setProgress(startBrushSliderPosition);
        mSlateFragment.mSlate.setPenSize(SliderInterpolator.getBrushSizeMin(startBrushSliderPosition, DENSITY),
                SliderInterpolator.getBrushSizeMax(startBrushSliderPosition, DENSITY));

        mBrushSizeBar.setOnSeekBarChangeListener(this);


        mBrushTransparencyBar = (SeekBar) findViewById(R.id.brush_transparency_bar);
        mBrushTransparencyBar.setOnSeekBarChangeListener(this);
        if (icicle == null) {
            mBrushTransparencyBar.setProgress(0);
        } else {
            mBrushTransparencyBar.setProgress(icicle.getInt(PreferenceConstants.PREF_BRUSH_TRANSPARENCY));
        }

        mBrushButton = (ImageButton) findViewById(R.id.brush_button);
        mBrushButton.setSelected(true);
        mEraserButton = (ImageButton) findViewById(R.id.eraser_button);

        mScaleDetector = new ScaleGestureDetector(getApplicationContext(), new ScaleListener());
        mRotateDetector = new RotationGestureDetector(getApplicationContext(), new RotateListener());
        mTranslationDetector = new TranslationGestureDetector(getApplicationContext(), new MoveListener());

        mPrefs = getPreferences(MODE_PRIVATE);

        if (icicle != null) {
            mSlateFragment.mColor = icicle.getInt(PreferenceConstants.PREF_BRUSH_COLOR);
            mMasterBucket.setColor(mSlateFragment.mColor);
            this.setPenColor(mSlateFragment.mColor);
        } else {
            mSlateFragment.mColor = mPrefs.getInt(PreferenceConstants.PREF_BRUSH_COLOR,
                this.getResources().getColor(R.color.default_drawing_color));
            mMasterBucket.setColor(mSlateFragment.mColor);
            this.setPenColor(mSlateFragment.mColor);
        }
    }
    
   
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
       MenuInflater inflater = getMenuInflater();
       inflater.inflate(R.menu.markers_activity, menu);
       return super.onCreateOptionsMenu(menu);
    }
    
    @Override
    public boolean onOptionsItemSelected(
	    MenuItem item) {
	switch (item.getItemId()) {
	case R.id.menu_back:
	    clickUndo(null);
	    break;
	case R.id.menu_resize:
        resetSlatePosition();
	    break;
	case R.id.menu_clear:
	    clickClear();
	    break;
	case R.id.menu_share:
	    clickShare(null);
	    break;
	}
	return true;
    }

    private void resetSlatePosition() {
        mScaleFactor = 1.f;
        mSlateFragment.mSlate.setScaleX(mScaleFactor);
        mSlateFragment.mSlate.setScaleY(mScaleFactor);
        mRotationDegrees = 0.f;
        mSlateFragment.mSlate.setRotation(mRotationDegrees);
        mTranslationX = 0.f;
        mTranslationY = 0.f;
        mSlateFragment.mSlate.setTranslationX(mTranslationX);
        mSlateFragment.mSlate.setTranslationY(mTranslationY);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getY(i) < this.getActionBar().getHeight() +
                    getResources().getDimension(R.dimen.notification_bar_height)) {
                return super.dispatchTouchEvent(event);
            }
        }
        int actionMasked = event.getActionMasked();
        if (event.getPointerCount() > 1) {
            if (!mIsAGestureOngoing) {
                lastCapturedEventTime = event.getDownTime();
                mIsAGestureOngoing = true;
                mSlateFragment.mSlate.finishStroke(event.getEventTime());
                // decide if we need to cancel the ongoing stroke :
                if (((System.nanoTime() / NS_PER_MS) - event.getDownTime() < 500)) {
                    mSlateFragment.mSlate.undo();
                }

            }

            mRotateDetector.onTouchEvent(event);
            mScaleDetector.onTouchEvent(event);
            //mTranslationDetector.onTouchEvent(event);
            if (actionMasked == MotionEvent.ACTION_CANCEL || actionMasked == MotionEvent.ACTION_UP) {
                mIsAGestureOngoing = false;
            }
            return true;
        }
        if (actionMasked == MotionEvent.ACTION_CANCEL || actionMasked == MotionEvent.ACTION_UP) {
            mIsAGestureOngoing = false;
        }
        if (mIsAGestureOngoing) {
            return true;
        }
        if (event.getPointerCount() == 1 && lastCapturedEventTime == event.getDownTime()) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

	private float mInitialScaleFactor = -1;
	private static final float SCALING_THRESHOLD = 0.3f;
	private boolean mScaling = false;

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public boolean onScale(ScaleGestureDetector detector) {

	    if (!mScaling) {
		if (mInitialScaleFactor < 0) {
		    mInitialScaleFactor = detector.getScaleFactor();
		} else {
		    final float deltaScale = detector.getScaleFactor()
			    - mInitialScaleFactor;
		    if (Math.abs(deltaScale) > SCALING_THRESHOLD) {
			mScaling = true;
			return true;
		    }
		}
		return false;
	    }

	    mScaleFactor *= detector.getScaleFactor();
	    mScaleFactor = Math.min(Math.max(mScaleFactor, 0.8f), 3.0f);

	    mSlateFragment.mSlate.setScaleX(mScaleFactor);
	    mSlateFragment.mSlate.setScaleY(mScaleFactor);
	    // mSlate.setPivotX(mScaleDetector.getFocusX());
	    // mSlate.setPivotY(mScaleDetector.getFocusY());
	    return true;
	}
    }

    private class RotateListener extends RotationGestureDetector.SimpleOnRotateGestureListener {
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public boolean onRotate(RotationGestureDetector detector) {
	    mRotationDegrees -= detector.getRotationDegreesDelta();
	    mSlateFragment.mSlate.setRotation(mRotationDegrees);
	    // mSlate.setPivotX(mScaleDetector.getFocusX());
	    // mSlate.setPivotY(mScaleDetector.getFocusY());
	    return true;
	}
    }

    private class MoveListener extends TranslationGestureDetector.SimpleOnMoveGestureListener {
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	public boolean onMove(TranslationGestureDetector detector) {
	    PointF delta = detector.getFocusDelta();
	    mTranslationX += delta.x;
	    mTranslationY += delta.y;
	    mSlateFragment.mSlate.setTranslationX(mTranslationX);
	    mSlateFragment.mSlate.setTranslationY(mTranslationY);
	    return true;
	}
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress,
                                  boolean fromUser) {
        switch (seekBar.getId()) {
            case R.id.brush_size_bar:
                mSlateFragment.mSlate.setPenSize(SliderInterpolator.getBrushSizeMin(progress, DENSITY),
                                                 SliderInterpolator.getBrushSizeMax(progress, DENSITY));
                break;
            case R.id.brush_transparency_bar:
                mSlateFragment.mSlate.setPenOpacity(255 - progress);
                break;
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {	
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {	
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onPause() {
	super.onPause();
	mSlateFragment.saveDrawing(PreferenceConstants.WIP_FILENAME, true);
	
	// Let's cancel the changes made in order to do not mess up the slate position :
	if (Math.abs((mSlateFragment.mOrientation - mSlateFragment.mOriginalOrientation) % 180) == 90) {
	    float r = this.mSlateFragment.mSlate.getRotation();
	    this.mSlateFragment.mSlate.setRotation(r - 90);
	    this.mSlateFragment.mSlate.setTranslationX(0);
	    this.mSlateFragment.mSlate.setTranslationY(0);
	}
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onResume() {
	super.onResume();
	if (Math.abs((mSlateFragment.mOrientation - mSlateFragment.mOriginalOrientation) % 180) == 90) {
	    int H = this.mSlateFragment.mSlate.getHeight() / 2;
	    int W = this.mSlateFragment.mSlate.getWidth() / 2;
	    float r = this.mSlateFragment.mSlate.getRotation();
	    this.mSlateFragment.mSlate.setRotation(r + 90);
	    this.mSlateFragment.mSlate.setTranslationX(-W + H);
	    this.mSlateFragment.mSlate.setTranslationY(-H + W);
	}
    }

    @Override
    protected void onStop() {
	SharedPreferences.Editor editor = mPrefs.edit();
	editor.putInt(PreferenceConstants.PREF_BRUSH_COLOR, mSlateFragment.mColor);
	editor.putInt(PreferenceConstants.PREF_BRUSH_SIZE, mBrushSizeBar.getProgress());
	editor.putInt(PreferenceConstants.PREF_BRUSH_TRANSPARENCY, mBrushTransparencyBar.getProgress());
	editor.apply();
        super.onStop();
    }

    private String dumpBundle(Bundle b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder("Bundle{");
        boolean first = true;
        for (String key : b.keySet()) {
            if (!first) sb.append(" ");
            first = false;
            sb.append(key+"=(");
            sb.append(b.get(key));
        }
        sb.append("}");
        return sb.toString();
    }
    
    @Override
    protected void onStart() {
	super.onStart();
	Intent startIntent = getIntent();
	if (DEBUG)
	    Log.d(TAG, "starting with intent=" + startIntent + " extras="
                + dumpBundle(startIntent.getExtras()));
	String a = startIntent.getAction();
        if (a == null){
            return;
        }
	if (a.equals(Intent.ACTION_EDIT)) {
	    // XXX: what happens to the old drawing? we should really move to
	    // auto-save
        if (mSlateFragment!=null && mSlateFragment.mSlate!= null) {
	        mSlateFragment.mSlate.clear();
        }
	    loadImageFromIntent(startIntent);
	} else if (a.equals(Intent.ACTION_SEND)) {
	    // XXX: what happens to the old drawing? we should really move to
	    // auto-save
	    mSlateFragment.mSlate.clear();
	    loadImageFromContentUri((Uri) startIntent
		    .getParcelableExtra(Intent.EXTRA_STREAM));
	}
    }

    @Override
    protected void onSaveInstanceState(Bundle icicle) {
	super.onSaveInstanceState(icicle);
	icicle.putInt(PreferenceConstants.PREF_BRUSH_COLOR, mSlateFragment.mColor);
    icicle.putInt(PreferenceConstants.PREF_BRUSH_SIZE, mBrushSizeBar.getProgress() );
    icicle.putInt(PreferenceConstants.PREF_BRUSH_TRANSPARENCY, mBrushTransparencyBar.getProgress());
    }

    @Override
    protected void onRestoreInstanceState(Bundle icicle) {
	super.onRestoreInstanceState(icicle);
    }
    
    public void clickClear(View v) {
	mSlateFragment.mSlate.clear();
    }

    public void clickSave(View v) {
	if (mSlateFragment == null || mSlateFragment.mSlate.isEmpty()) {
	    return;
	}

	v.setEnabled(false);
	final String filename = System.currentTimeMillis() + ".png";
	mSlateFragment.saveDrawing(filename);
	Toast.makeText(this, "Drawing saved: " + filename, Toast.LENGTH_SHORT)
		.show();
	v.setEnabled(true);
    }

    public void clickClear() {
	mSlateFragment.mSlate.clear();
    }

    public void clickSaveAndClear(View v) {
        if (mSlateFragment == null || mSlateFragment.mSlate.isEmpty()) return;

        v.setEnabled(false);
        final String filename = System.currentTimeMillis() + ".png"; 
        mSlateFragment.saveDrawing(filename, 
                /*temporary=*/ false, /*animate=*/ true, /*share=*/ false, /*clear=*/ true);
        Toast.makeText(this, "Drawing saved: " + filename, Toast.LENGTH_SHORT).show();
        v.setEnabled(true);
    }

    public void clickShare(View v) {
	final String filename = System.currentTimeMillis() + ".png";
	mSlateFragment.saveDrawing(filename,
				   /* temporary= */false,
				   /* animate= */false,
				   /* share= */true,
				   /* clear= */	false);
    }

  public void clickLoad(View unused) {
        Intent i = new Intent(Intent.ACTION_PICK,
                       android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        startActivityForResult(i, LOAD_IMAGE); 
    }

    public void clickDebug(View unused) {
        boolean debugMode = (mSlateFragment.mSlate.getDebugFlags() == 0); // toggle 
        mSlateFragment.mSlate.setDebugFlags(debugMode
            ? Slate.FLAG_DEBUG_EVERYTHING
            : 0);
        //mDebugButton.setSelected(debugMode);
        Toast.makeText(this, "Debug mode " + ((mSlateFragment.mSlate.getDebugFlags() == 0) ? "off" : "on"),
            Toast.LENGTH_SHORT).show();
    }

    //TODO remove when gestures implemented
    public void clickUndo(View unused) {
	mSlateFragment.mSlate.undo();
    }

    public void setPenColor(int color) {
        mSlateFragment.setPenColor(color);
    }
    
    public void setPenType(int type) {
	mSlateFragment.setPenType(type);
    }
     
    public void onBrushButtonClick(View v) {
	if (!mIsPenUsed) {
	    mIsPenUsed = true;
	    mBrushButton.setSelected(true);
	    mEraserButton.setSelected(false);
	    setPenColor(mSlateFragment.mColor);
	}
    }
    
    public void onEraserButtonClick(View v) {
	if (mIsPenUsed) {
	    mIsPenUsed = false;
	    mEraserButton.setSelected(true);
	    mBrushButton.setSelected(false);
	    setPenColor(0);
	}
    }

    protected void loadImageFromIntent(Intent imageReturnedIntent) {
        Uri contentUri = imageReturnedIntent.getData();
        loadImageFromContentUri(contentUri);
    }
    
    protected void loadImageFromContentUri(Uri contentUri) {
	Toast.makeText(this, "Loading from " + contentUri, Toast.LENGTH_SHORT)
		.show();
	mSlateFragment.loadDrawing(PreferenceConstants.WIP_FILENAME, true);
	mSlateFragment.mJustLoadedImage = true;

	try {
	    Bitmap b = MediaStore.Images.Media.getBitmap(getContentResolver(), contentUri);
	    if (b != null) {
		mSlateFragment.mSlate.paintBitmap(b);
		if (DEBUG)
		    Log.d(TAG, "successfully loaded bitmap: " + b);
	    } else {
		Log.e(TAG, "couldn't get bitmap from " + contentUri);
	    }
	} catch (java.io.FileNotFoundException ex) {
	    Log.e(TAG, "error loading image from " + contentUri + ": " + ex);
	} catch (java.io.IOException ex) {
	    Log.e(TAG, "error loading image from " + contentUri + ": " + ex);
	}
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent imageReturnedIntent) { 
        super.onActivityResult(requestCode, resultCode, imageReturnedIntent); 

        switch (requestCode) { 
        case LOAD_IMAGE:
            if (resultCode == RESULT_OK) {
                loadImageFromIntent(imageReturnedIntent);
            }
        }
    }

    public void showColorPicker(View view) {
	if (mColorDialog == null) {
	    mColorDialog = new ColorDialogFragment();
	}
	mColorDialog.mOldColor = mSlateFragment.mColor;
	mColorDialog.show(this.getFragmentManager(), "colorPicker");
    }

}
