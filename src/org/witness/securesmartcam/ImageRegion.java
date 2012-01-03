package org.witness.securesmartcam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import net.londatiga.android.ActionItem;
import net.londatiga.android.QuickAction;
import net.londatiga.android.QuickAction.OnActionItemClickListener;

import org.json.JSONException;
import org.json.JSONObject;
import org.witness.informa.utils.secure.MediaHasher;
import org.witness.securesmartcam.filters.BlurObscure;
import org.witness.securesmartcam.filters.ConsentTagger;
import org.witness.securesmartcam.filters.CrowdPixelizeObscure;
import org.witness.securesmartcam.filters.MaskObscure;
import org.witness.securesmartcam.filters.SolidObscure;
import org.witness.securesmartcam.filters.PixelizeObscure;
import org.witness.securesmartcam.filters.RegionProcesser;
import org.witness.sscphase1.ObscuraApp;
import org.witness.sscphase1.R;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.FrameLayout;
import android.widget.PopupWindow.OnDismissListener;
import android.widget.RelativeLayout;

public class ImageRegion implements OnActionItemClickListener 
{

	public static final String LOGTAG = "SSC.ImageRegion";
	
	// Rect for this when unscaled
	public RectF mBounds;
	//public RectF mTmpBounds;
	
	// Start point for touch events
	PointF mStartPoint = null;
	PointF mNonMappedStartPoint = null;
	
	// Our current mode
	public static final int NORMAL_MODE = 0;
	public static final int EDIT_MODE = 1;
	
// The current touch event mode
	public final static int NONE = 0;
	public final static int MOVE = 1;
	

	// What should be done to this region
	public static final int NOTHING = 0;
	public static final int OBSCURE = 1;
	
	public static final int REDACT = 0; // PaintSquareObscure
	public static final int PIXELATE = 1; // PixelizeObscure
	public static final int BG_PIXELATE = 2; // BlurObscure
	public static final int MASK = 3; // MaskObscure	
	//public static final int BLUR = 4; // PixelizeObscure
	public static final int CONSENT = 4; // PixelizeObscure
	
	boolean selected = false;
	
	/* Add each ObscureMethod to this list and update the 
	 * createObscuredBitmap method in ImageEditor
	 */
	int mObscureType = PIXELATE;

	private final static String[] mFilterLabels = {"Redact","Pixelate","CrowdPixel","Mask","Identify"};
	private final static int[] mFilterIcons = {R.drawable.ic_context_fill,R.drawable.ic_context_pixelate,R.drawable.ic_context_pixelate, R.drawable.ic_context_mask, R.drawable.ic_context_id};

	public final Drawable unidentifiedBorder, identifiedBorder;
	public Drawable imageRegionBorder;
	
	// The ImageEditor object that contains us
	ImageEditor mImageEditor;
	
	// Popup menu for region 
	// What's the license for this?
	QuickAction mPopupMenu;
		
	ActionItem[] mActionFilters;
	
	RegionProcesser mRProc;
	
	private final static float MIN_MOVE = 5f;

	private final static float CORNER_MAX = 50f;
	
	private int cornerMode = -1;
	
	public RegionProcesser getRegionProcessor() {
		return mRProc;
	}

	public void setRegionProcessor(RegionProcesser rProc) {
		mRProc = rProc;
		mImageEditor.associateImageRegionData(this);
	}
	
	public JSONObject getRepresentation() throws JSONException {
		JSONObject representation = new JSONObject();
		Enumeration e = mRProc.getProperties().propertyNames();
		
		while(e.hasMoreElements()) {
			String prop = (String) e.nextElement();
			representation.put(prop, mRProc.getProperties().get(prop));
		}
		
		// appends original bitmap bytes as Base64-encoded string
		if(mRProc.getBitmap() != null) {
			
    		ByteArrayOutputStream bs = new ByteArrayOutputStream();
    		Bitmap b = mRProc.getBitmap();
    		
    		b.compress(Bitmap.CompressFormat.PNG, 50, bs);
    		byte[] unredactedBytes = bs.toByteArray();
    		
    		Log.d(ObscuraApp.TAG, "got bitmap!\n" + bs.size());
			//representation.put("unredacted", Base64.encodeToString(unredactedBytes, Base64.DEFAULT));
    		try {
				representation.put("unredacted", MediaHasher.hash(unredactedBytes, "MD5"));
				
			} catch (NoSuchAlgorithmException n) {}
    		catch (IOException n) {}
		} else
			Log.d(ObscuraApp.TAG, "there is no bitmap here");
		
		return representation;
	}
	
	public void setCornerMode (float x, float y)
	{
		float[] points = {x,y};        	
    	iMatrix.mapPoints(points);
    	
    	float cSize = CORNER_MAX;
    	
    	cSize = iMatrix.mapRadius(cSize);
    	
    	if (Math.abs(mBounds.left-points[0])<cSize
    			&& Math.abs(mBounds.top-points[1])<cSize
    			)
    	{
    		cornerMode = 1;
    		return;
    	}
    	else if (Math.abs(mBounds.left-points[0])<cSize
    			&& Math.abs(mBounds.bottom-points[1])<cSize
    			)
    	{
    		cornerMode = 2;
			return;
		}
    	else if (Math.abs(mBounds.right-points[0])<cSize
    			&& Math.abs(mBounds.top-points[1])<cSize
    			)
    	{
    			cornerMode = 3;
    			return;
		}
    	else if (Math.abs(mBounds.right-points[0])<cSize
        			&& Math.abs(mBounds.bottom-points[1])<cSize
        			)
    	{
    		cornerMode = 4;
    		return;
    	}
    	
    	cornerMode = -1;
	}

	
	/* For touch events, whether or not to show the menu
	 */
	boolean moved = false;
				
	Matrix mMatrix, iMatrix;

	int fingerCount = 0;
	
	public ImageRegion(
			ImageEditor imageEditor, 
			float left, float top, 
			float right, float bottom, Matrix matrix) 
	{
		//super(imageEditor);
		super();
		
		// Set the mImageEditor that this region belongs to to the one passed in
		mImageEditor = imageEditor;
		// set the borders for tags in Non-Edit mode
		identifiedBorder = imageEditor.getResources().getDrawable(R.drawable.border_idtag);
		unidentifiedBorder = imageEditor.getResources().getDrawable(R.drawable.border);

		mMatrix = matrix;
		iMatrix = new Matrix();
    	mMatrix.invert(iMatrix);
		
		// Calculate the minMoveDistance using the screen density
		//float scale = this.getResources().getDisplayMetrics().density;
	//	minMoveDistance = minMoveDistanceDP * scale + 0.5f;
		
		
		mBounds = new RectF(left, top, right, bottom);	
		
				
		// Inflate Layout
		// imageregioninner is a FrameLayout
		/*
		LayoutInflater inflater = LayoutInflater.from(mImageEditor);        
		inflater.inflate(R.layout.imageregioninner, this, true);
		setBackgroundDrawable(mImageEditor.getResources().getDrawable(R.drawable.border));
		updateMatrix();

        mMoveRegion = (View)findViewById(R.id.MoveRegion);

        // Setting the onTouchListener for the moveRegion
        // Might also want to do this for the other views (corners)
<<<<<<< HEAD
        mMoveRegion.setOnTouchListener(this);
                
=======
        moveRegion.setOnTouchListener(this);
        
        imageRegionBorder = unidentifiedBorder;
>>>>>>> informav1
        initPopup();
        
        // This doesn't work with the touch listener always returning true.  
        // In some cases touch listener returns false and this gets triggered
       
        mMoveRegion.setOnClickListener(new OnClickListener (){

			// @Override
			public void onClick(View v)
			{
				Log.v(LOGTAG,"onClick");
				
				inflatePopup(false);
			}
			
		});*/
        
        //set default processor
        this.setRegionProcessor(new PixelizeObscure());
    }		
	
	public void setMatrix (Matrix matrix)
	{
		mMatrix = matrix;
		iMatrix = new Matrix();
    	mMatrix.invert(iMatrix);
	}
	
	public void inflatePopup(boolean showDelayed) {

		if (mPopupMenu == null)
			initPopup();
		
		
		if (showDelayed) {
			// We need layout to pass again, let's wait a second or two
			new Handler() {
				@Override
				 public void handleMessage(Message msg) {

					float[] points = {mBounds.centerX(), mBounds.centerY()};		
					mMatrix.mapPoints(points);
					mPopupMenu.show(mImageEditor.getImageView(), (int)points[0], (int)points[1]);
			        }
			}.sendMessageDelayed(new Message(), 500);
		} else {			

			float[] points = {mBounds.centerX(), mBounds.centerY()};		
			mMatrix.mapPoints(points);
			mPopupMenu.show(mImageEditor.getImageView(), (int)points[0], (int)points[1]);
		}
		

	}
	
	private void initPopup ()
	{
		mPopupMenu = new QuickAction(mImageEditor);
		
		/*
		editAction = new ActionItem();
		editAction.setTitle("Edit Tag");
		editAction.setIcon(this.getResources().getDrawable(R.drawable.ic_context_edit));
		
		qa.addActionItem(editAction);
		*/

		ActionItem aItem;
		
		for (int i = 0; i < mFilterLabels.length; i++)
		{
		
			aItem = new ActionItem();
			aItem.setTitle(mFilterLabels[i]);
			
			aItem.setIcon(mImageEditor.getResources().getDrawable(mFilterIcons[i]));			
			
			mPopupMenu.addActionItem(aItem);

		}
		
		aItem = new ActionItem();
		aItem.setTitle("Delete Tag");
		aItem.setIcon(mImageEditor.getResources().getDrawable(R.drawable.ic_context_delete));

		mPopupMenu.addActionItem(aItem);

		mPopupMenu.setOnActionItemClickListener(this);
	}
	
	
	boolean isSelected ()
	{
		return selected;
	}
	
	void setSelected (boolean _selected)
	{
		selected = _selected;
	}
			
			
	private void updateBounds(float left, float top, float right, float bottom) 
	{
		//Log.i(LOGTAG, "updateBounds: " + left + "," + top + "," + right + "," + bottom);
		mBounds.set(left, top, right, bottom);
		
		//updateLayout();
	}
	
	float scaleX, scaleY, leftOffset, topOffset;
	
	public void updateMatrix ()
	{
		float[] mValues = new float[9];
		mMatrix.getValues(mValues);		
    	mMatrix.invert(iMatrix);
		scaleX = mValues[Matrix.MSCALE_X];
		scaleY = mValues[Matrix.MSCALE_Y];
		
		leftOffset = mValues[Matrix.MTRANS_X];
		topOffset = mValues[Matrix.MTRANS_Y];
		
	}
	
	public RectF getBounds ()
	{
		return mBounds;
	}
	
	
	public boolean onTouch(View v, MotionEvent event) 
	{
		
		fingerCount = event.getPointerCount();
	//	Log.v(LOGTAG,"onTouch: fingers=" + fingerCount);
		
		switch (event.getAction() & MotionEvent.ACTION_MASK) {
			
			case MotionEvent.ACTION_DOWN:
				
				mImageEditor.doRealtimePreview = true;
				mImageEditor.updateDisplayImage();
				//mTmpBounds = new RectF(mBounds);
				
				if (fingerCount == 1)
				{
					//float[] points = {event.getX(), event.getY()};                	
                	//iMatrix.mapPoints(points);
					//mStartPoint = new PointF(points[0],points[1]);
					mStartPoint = new PointF(event.getX(),event.getY());
					//Log.v(LOGTAG,"startPoint: " + mStartPoint.x + " " + mStartPoint.y);
				}
				
				moved = false;
				
				return false;
			case MotionEvent.ACTION_POINTER_UP:

				Log.v(LOGTAG, "second finger removed - pointer up!");

				return moved;
				
			case MotionEvent.ACTION_UP:

				mImageEditor.doRealtimePreview = true;
				mImageEditor.updateDisplayImage();
				//mTmpBounds = null;

				return moved;
			
			case MotionEvent.ACTION_MOVE:
			
				
                if (fingerCount > 1)
                {
                	
                	float[] points = {event.getX(0), event.getY(0), event.getX(1), event.getY(1)};                	
                	iMatrix.mapPoints(points);
                	
					mStartPoint = new PointF(points[0],points[1]);
					
                	RectF newBox = new RectF();
                	newBox.left = Math.min(points[0],points[2]);
                	newBox.top = Math.min(points[1],points[3]);
                	newBox.right = Math.max(points[0],points[2]);
                	newBox.bottom = Math.max(points[1],points[3]);
                	
                	moved = true;
                	
                	if (newBox.left != newBox.right && newBox.top != newBox.bottom)
                	{
                	                
                		updateBounds(newBox.left, newBox.top, newBox.right, newBox.bottom);
                	}
                	

                }
                else if (fingerCount == 1)
                {
                	
                	
                	if (Math.abs(mStartPoint.x- event.getX()) > MIN_MOVE)
                	{
	                	moved = true;
	                	
	                	float[] points = {mStartPoint.x, mStartPoint.y, event.getX(), event.getY()};
	                	
	                	iMatrix.mapPoints(points);
	                	
	                	float diffX = points[0]-points[2];
	                	float diffY = points[1]-points[3];
	                	
	                	if (cornerMode == -1)
	                		updateBounds(mBounds.left-diffX,mBounds.top-diffY,mBounds.right-diffX,mBounds.bottom-diffY);
	                	else if (cornerMode == 1)
	                		updateBounds(mBounds.left-diffX,mBounds.top-diffY,mBounds.right,mBounds.bottom);
	                	else if (cornerMode == 2)
	                		updateBounds(mBounds.left-diffX,mBounds.top,mBounds.right,mBounds.bottom-diffY);
	                	else if (cornerMode == 3)
	                		updateBounds(mBounds.left,mBounds.top-diffY,mBounds.right-diffX,mBounds.bottom);
	                	else if (cornerMode == 4)
	                		updateBounds(mBounds.left,mBounds.top,mBounds.right-diffX,mBounds.bottom-diffY);
	                		
	                	mStartPoint = new PointF(event.getX(),event.getY());
                	}
                	else
                	{
                		moved = false;
                	}
	            	
                }
                
                if(moved)
                	this.mRProc.updateBitmap();

				mImageEditor.updateDisplayImage();
					
				return true;
			/*	
			case MotionEvent.ACTION_OUTSIDE:
				Log.v(LOGTAG,"ACTION_OUTSIDE");
				
				mImageEditor.doRealtimePreview = true;
				mImageEditor.updateDisplayImage();
				

				return true;
				
			case MotionEvent.ACTION_CANCEL:
				Log.v(LOGTAG,"ACTION_CANCEL");
				
				mImageEditor.doRealtimePreview = true;
				mImageEditor.updateDisplayImage();
				
				return true;
				
			default:
				Log.v(LOGTAG, "DEFAULT: " + (event.getAction() & MotionEvent.ACTION_MASK));
		
				mImageEditor.doRealtimePreview = true;
				mImageEditor.updateDisplayImage();
				
				return true;*/
				
		}
		
		return false;
		
	}

	
	@Override
	public void onItemClick(int pos) {
		
		if (pos == mFilterLabels.length) //meaing after the last one
		{
        	mImageEditor.deleteRegion(ImageRegion.this);
		}
		else
		{
        	mObscureType = pos;
        	updateRegionProcessor(mObscureType);
        	
        		
		}

		mImageEditor.updateDisplayImage();

	}
	
	private void updateRegionProcessor (int obscureType)
	{
		
		switch (obscureType) {
		
		case ImageRegion.BG_PIXELATE:
			Log.v(ObscuraApp.TAG,"obscureType: BGPIXELIZE");
			setRegionProcessor(new CrowdPixelizeObscure());
		break;
		
		case ImageRegion.MASK:
			Log.v(ObscuraApp.TAG,"obscureType: ANON");
			setRegionProcessor(new MaskObscure(mImageEditor.getApplicationContext(), mImageEditor.getPainter()));

			break;
			
		case ImageRegion.REDACT:
			Log.v(ObscuraApp.TAG,"obscureType: SOLID");
			setRegionProcessor(new SolidObscure());
			break;
			
		case ImageRegion.PIXELATE:
			Log.v(ObscuraApp.TAG,"obscureType: PIXELIZE");
			setRegionProcessor(new PixelizeObscure());
			break;
		case ImageRegion.CONSENT:
			Log.v(ObscuraApp.TAG,"obscureType: CONSENTIFY!");
			// If the region processor is already a consent tagger, the user wants to edit.
			// so no need to change the region processor.
			if(!(getRegionProcessor() instanceof ConsentTagger))
				setRegionProcessor(new ConsentTagger());
			
			mImageEditor.launchInforma(this);
			break;
		default:
			Log.v(ObscuraApp.TAG,"obscureType: NONE/BLUR");
			setRegionProcessor(new BlurObscure());
			break;
		}
		
		if(getRegionProcessor().getClass() == ConsentTagger.class)
			imageRegionBorder = identifiedBorder;
		else
			imageRegionBorder = unidentifiedBorder;
	}

	
	
}
