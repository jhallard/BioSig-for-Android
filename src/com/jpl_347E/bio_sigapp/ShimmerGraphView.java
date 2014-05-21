package com.jpl_347E.bio_sigapp; 
  
import android.content.Context; 
import android.graphics.Bitmap; 
import android.graphics.Canvas; 
import android.graphics.Color; 
import android.graphics.Paint; 
import android.util.AttributeSet; 
import android.util.Log;
import android.view.View; 
  
public class ShimmerGraphView extends View { 
    //======================================================================================= 
    // Data Members 
    //======================================================================================= 
    private Bitmap bitmap; 
    private Canvas canvas = new Canvas();               // Canvas to draw on 
    private int[] colors = new int[2];                  // Colors for each sensor 
    private float lastX;                                // Last x data point 
    private float[] lastPoint = new float[2];           // Stores the last points for each sensor 
    private float maxPoint = 1024f;                     // Max Point that can be reached 
    private Paint paint = new Paint(),                  // Will paint the line 
                  paintText = new Paint();              // Will paint text 
    private float scale = 0.5f;                             // Scale of graph 
    private float speed = 1.0f;                         // Speed of drawing 
    public float tempPoint;                             // Holds a temporary copy of newValue from addDataPoint 
    private float canvasWidth; 
    private float yOffset; 
      
      
      
    //======================================================================================= 
    // Constructors 
    //======================================================================================= 
    public ShimmerGraphView(Context context) { 
        super(context); 
        initialize(); 
    } 
  
    public ShimmerGraphView(Context context, AttributeSet attrs) { 
        super(context, attrs); 
        initialize(); 
    } 
      
    //======================================================================================= 
    // Methods 
    //======================================================================================= 
    private void addDataPoint(float point, final int color, final float lastPnt, final int position) { 
        final Paint locPaint = paint; 
          
        float newX = lastX + speed;
        final float newPoint = yOffset + (point * scale); 
          
        locPaint.setColor(color); 
          
        // Draw a line between previous point and new point 
        canvas.drawLine(lastX, lastPnt, newX, newPoint, locPaint); 
        tempPoint = newPoint; 
        lastPoint[position] = newPoint; 
          
        if (position == 0) 
            lastX += speed; 
        
       // invalidate();
    } 
      
    private void initialize() { 
        colors[0] = Color.BLUE; 
        colors[1] = Color.RED; 
          
        paint.setFlags(Paint.ANTI_ALIAS_FLAG);          // Use anti-aliasing for line 
    } 
      
    @Override
    protected void onDraw(Canvas cavas) { 
        synchronized(this) { 
            if (bitmap != null) { 
                // Case where the end of the screen has been reached 
                if (lastX >= canvasWidth) { 
                    lastX = 0; 
                    final Canvas cnvs = canvas; 
                    cnvs.drawColor(0xFF000000); 
                    paint.setColor(0xFF444444); 
                    cnvs.drawLine(0, yOffset, canvasWidth, yOffset, paint); 
                } 
                cavas.drawBitmap(bitmap, 0, 0, null); 
            } 
        } 
    } 
  
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) { 
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565); 
        canvas.setBitmap(bitmap); 
        canvas.drawColor(0xFF000000); 
        yOffset = h; 
        scale = - (yOffset * (1.0f / maxPoint)); 
        canvasWidth = w; 
        lastX = canvasWidth; 
        super.onSizeChanged(w, h, oldw, oldh); 
    } 
  
    public void setData(float value) { 
        addDataPoint(value, colors[0], lastPoint[0], 0); 
        invalidate(); 
    } 
      
    public void setData(int[] points, String deviceID) { 
        final Paint paintTxt = paintText; 
        paintTxt.setColor(Color.WHITE); 
        canvas.drawText(deviceID, 5, 10, paintTxt); 
          
        for (int i = 0; i < points.length; i++)  
            addDataPoint(points[i], colors[i % 2], lastPoint[i], i); 
          
        invalidate(); 
    } 
      
    public void setDataWithAdjustments(int[] points, String deviceID, String type) { 
        final Paint paintTxt = paintText; 
          
        // Set max value if ECG or EMG 
        if (type.equals("u12")) 
            setMaxPoint(4095); 
        // Set max value if GSR 
        else if (type.equals("u16")) 
            setMaxPoint(65535); 
          
        canvas.drawText(deviceID, 5, 10, paintTxt); 
          
        for (int i = 0; i < points.length; i++) 
            addDataPoint(points[i], colors[i % 2], lastPoint[i], i); 
          
        invalidate(); 
    } 
      
    public void setMaxPoint(int max) { 
        maxPoint = max; 
        scale =   - (yOffset * (1.0f / maxPoint)); 
    } 
      
    public void setSpeed(float spd) { 
        speed = spd; 
    } 
      
    public void setYOffset(int off) { 
        yOffset = off; 
    } 
      
} 