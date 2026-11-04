package com.lumocamera.pro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
 private lateinit var preview:PreviewView
 private val executor=Executors.newSingleThreadExecutor()
 private var still:ImageCapture?=null
 private var video:VideoCapture<Recorder>?=null
 private var camera:androidx.camera.core.Camera?=null
 private var recording:Recording?=null
 private var videoMode=false
 private var isRecording=false
 private var front=false
 private var zoom=1f
 private var quality=Quality.FHD
 private var poseOn=false
 private lateinit var shutter:TextView
 private lateinit var status:TextView
 private lateinit var qualityText:TextView
 private lateinit var pose:PoseView
 private lateinit var filterPanel:LinearLayout
 private var filtersOn=false
 private var poseAlpha=0.42f
 private var poseScale=0.72f
 private var poseBitmap:Bitmap?=null
 private val posePicker=registerForActivityResult(ActivityResultContracts.GetContent()){ uri -> if(uri!=null) loadPose(uri) }

 private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
  if(it[Manifest.permission.CAMERA]==true) startCamera()
  else Toast.makeText(this,"Camera permission required",Toast.LENGTH_LONG).show()
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=Color.TRANSPARENT
  window.navigationBarColor=Color.BLACK
  window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
  makeUi()
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera()
  else permission.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))
 }
 private fun d(v:Float)=(v*resources.displayMetrics.density).toInt()
 private fun glass(r:Float=26f,a:Int=185)=GradientDrawable(GradientDrawable.Orientation.TL_BR,
  intArrayOf(Color.argb(a,48,49,66),Color.argb(a*3/4,13,15,27))).apply {
  cornerRadius=d(r).toFloat();setStroke(d(1f),0x66FFFFFF)
 }
 private fun txt(s:String,size:Float,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply {
  text=s;textSize=size;setTextColor(color);gravity=Gravity.CENTER
  if(bold)setTypeface(typeface,Typeface.BOLD)
 }
 private fun chip(s:String,active:Boolean=false,size:Float=12f)=txt(s,size,if(active)Color.WHITE else 0xFFD6D3E2.toInt(),true).apply {
  setPadding(d(14f),d(10f),d(14f),d(10f));background=glass(24f,if(active)225 else 100)
  elevation=d(5f).toFloat();isClickable=true
 }
 private fun makeUi(){
  val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
  preview=PreviewView(this).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE}
  root.addView(preview,FrameLayout.LayoutParams(-1,-1))
  pose=PoseView(this);root.addView(pose,FrameLayout.LayoutParams(-1,-1))
  preview.setOnTouchListener{_,e->if(e.action==MotionEvent.ACTION_UP){
   val p=preview.meteringPointFactory.createPoint(e.x,e.y)
   camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
   val ring=View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setStroke(d(2f),0xFFFFD98A.toInt())}}
   val lp=FrameLayout.LayoutParams(d(54f),d(54f));lp.leftMargin=e.x.toInt()-d(27f);lp.topMargin=e.y.toInt()-d(27f)
   root.addView(ring,lp);ring.scaleX=.6f;ring.scaleY=.6f
   ring.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(750).withEndAction{root.removeView(ring)}.start()
  };true}
  val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(d(17f),d(8f),d(9f),d(8f));background=glass(30f,190);elevation=d(12f).toFloat()}
  val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  brand.addView(txt("LUMO",19f,Color.WHITE,true));brand.addView(txt("CAMERA  PRO",8f,0xFFCAB8FF.toInt(),true));top.addView(brand)
  top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
  top.addView(chip("POSE").apply{setOnClickListener{poseOn=!poseOn;pose.visibility=if(poseOn)View.VISIBLE else View.GONE}})
  top.addView(chip("⚙",true,19f).apply{setOnClickListener{settings()}})
  root.addView(top,FrameLayout.LayoutParams(-1,d(65f),Gravity.TOP).apply{setMargins(d(12f),d(38f),d(12f),0)})

  val side=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=glass(24f,150);setPadding(d(5f),d(8f),d(5f),d(8f))}
  side.addView(chip("EV").apply{setOnClickListener{exposure()}})
  side.addView(chip("◐",false,18f).apply{setOnClickListener{filtersOn=!filtersOn;filterPanel.visibility=if(filtersOn)View.VISIBLE else View.GONE}})
  root.addView(side,FrameLayout.LayoutParams(d(58f),-2,Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply{rightMargin=d(12f)})

  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(d(13f),d(14f),d(13f),d(13f));background=glass(34f,215);elevation=d(20f).toFloat()}
  val zooms=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
  listOf("0.6×" to .6f,"1×" to 1f,"2×" to 2f,"3×" to 3f).forEach{item->
   zooms.addView(chip(item.first,item.second==1f).apply{setOnClickListener{setZoom(item.second)};setOnLongClickListener{setZoom(item.second);true}},
    LinearLayout.LayoutParams(0,d(42f),1f).apply{setMargins(d(2f),0,d(2f),0)})
  };panel.addView(zooms)
  filterPanel=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;visibility=View.GONE;setPadding(0,d(7f),0,0)}
  listOf("NATURAL","VIVID","WARM","COOL").forEach{n->filterPanel.addView(chip(n).apply{setOnClickListener{Toast.makeText(this@MainActivity,"Filter preview is not applied to captured image yet",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(0,d(36f),1f))}
  panel.addView(filterPanel)
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(0,d(10f),0,d(3f))}
  actions.addView(chip("▧",false,24f).apply{setOnClickListener{runCatching{startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"image/*"))}}},LinearLayout.LayoutParams(0,d(70f),1f))
  shutter=txt("●",56f,Color.WHITE,true).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(0x22FFFFFF);setStroke(d(4f),Color.WHITE)};setOnClickListener{if(videoMode)recordVideo()else takePhoto()}}
  actions.addView(shutter,LinearLayout.LayoutParams(d(78f),d(78f)))
  actions.addView(chip("⟳",false,27f).apply{setOnClickListener{front=!front;startCamera()}},LinearLayout.LayoutParams(0,d(70f),1f))
  panel.addView(actions)
  val modes=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
  modes.addView(chip("PHOTO",true).apply{setOnClickListener{videoMode=false;refresh()}})
  modes.addView(chip("VIDEO").apply{setOnClickListener{videoMode=true;refresh()}})
  modes.addView(chip("MY POSE",true).apply{setOnClickListener{openPoseSection()}})
  panel.addView(modes)
  qualityText=txt("PHOTO  •  MAX QUALITY",9f,0xFFD2CBE4.toInt(),true).apply{setPadding(0,d(7f),0,0);setOnClickListener{quality=if(quality==Quality.FHD)Quality.UHD else Quality.FHD;refresh();startCamera()}}
  panel.addView(qualityText)
  status=txt("READY  •  TAP TO FOCUS",9f,0xFFB8B5C6.toInt(),true).apply{setPadding(0,d(5f),0,0)}
  panel.addView(status)
  root.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(d(11f),0,d(11f),d(12f))})
  setContentView(root)
  panel.translationY=d(40f).toFloat();panel.alpha=0f
  panel.animate().translationY(0f).alpha(1f).setDuration(650).setInterpolator(DecelerateInterpolator()).start()
 }

 private fun loadPose(uri:Uri){
  try{
   val source=contentResolver.openInputStream(uri)
   val bmp=source?.use{BitmapFactory.decodeStream(it)}
   if(bmp==null){Toast.makeText(this,"Could not load this image",Toast.LENGTH_SHORT).show();return}
   poseBitmap=bmp
   pose.reference=bmp;pose.imageAlpha=poseAlpha;pose.imageScale=poseScale
   pose.visibility=View.VISIBLE;poseOn=true;pose.invalidate()
   Toast.makeText(this,"Reference pose loaded",Toast.LENGTH_SHORT).show()
  }catch(e:Exception){Toast.makeText(this,"Image could not be opened",Toast.LENGTH_SHORT).show()}
 }
 private fun openPoseSection(){
  val options=arrayOf("Upload reference image","Adjust transparency and size","Remove reference image")
  android.app.AlertDialog.Builder(this).setTitle("MY POSE").setItems(options){_,which->
   when(which){
    0->posePicker.launch("image/*")
    1->adjustPose()
    2->{poseBitmap=null;pose.reference=null;pose.invalidate();pose.visibility=View.GONE;poseOn=false}
   }
  }.show()
 }
 private fun adjustPose(){
  val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(d(20f),d(12f),d(20f),d(8f))}
  layout.addView(txt("TRANSPARENCY",12f,Color.WHITE,true))
  val alphaSeek=SeekBar(this).apply{max=100;progress=(poseAlpha*100).toInt()}
  layout.addView(alphaSeek)
  layout.addView(txt("REFERENCE SIZE",12f,Color.WHITE,true))
  val sizeSeek=SeekBar(this).apply{max=100;progress=((poseScale-.25f)/1.25f*100).toInt().coerceIn(0,100)}
  layout.addView(sizeSeek)
  val dialog=android.app.AlertDialog.Builder(this).setTitle("MY POSE CONTROLS").setView(layout).setPositiveButton("DONE",null).setNegativeButton("CANCEL",null).create()
  alphaSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){poseAlpha=v/100f;pose.imageAlpha=poseAlpha;pose.invalidate()}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){}
  })
  sizeSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){poseScale=.25f+1.25f*v/100f;pose.imageScale=poseScale;pose.invalidate()}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){}
  })
  dialog.show()
 }

 private fun refresh(){
  shutter.text=if(isRecording)"■" else "●"
  status.text=if(isRecording)"● RECORDING" else if(videoMode)"VIDEO  •  TAP SHUTTER TO RECORD" else "PHOTO  •  TAP TO FOCUS"
  qualityText.text=if(videoMode)"VIDEO QUALITY  •  "+(if(quality==Quality.UHD)"4K" else "FHD")+"  ↻" else "PHOTO  •  MAX QUALITY"
 }
 private fun startCamera(){
  val future=ProcessCameraProvider.getInstance(this)
  future.addListener({
   val provider=future.get();val selector=if(front)CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
   val p=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)}
   still=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
   val rec=Recorder.Builder().setQualitySelector(QualitySelector.from(quality,FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
   video=VideoCapture.withOutput(rec)
   try{provider.unbindAll();camera=provider.bindToLifecycle(this,selector,p,still,video);setZoom(zoom)}
   catch(e:Exception){Toast.makeText(this,"Camera error: "+(e.localizedMessage?:"unsupported"),Toast.LENGTH_LONG).show()}
  },ContextCompat.getMainExecutor(this))
 }
 private fun setZoom(v:Float){
  val z=camera?.cameraInfo?.zoomState?.value
  zoom=if(z==null)v else v.coerceIn(z.minZoomRatio,z.maxZoomRatio)
  camera?.cameraControl?.setZoomRatio(zoom)
 }
 private fun exposure(){
  val range=camera?.cameraInfo?.exposureState?.exposureCompensationRange?:return
  val seek=SeekBar(this).apply{max=range.upper-range.lower;progress=-range.lower
   setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
    override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user)camera?.cameraControl?.setExposureCompensationIndex(p+range.lower)}
    override fun onStartTrackingTouch(s:SeekBar?){}
    override fun onStopTrackingTouch(s:SeekBar?){}
   })}
  LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(d(18f),d(10f),d(18f),d(10f));addView(txt("EXPOSURE",14f,Color.WHITE,true));addView(seek)}
   .also{android.app.AlertDialog.Builder(this).setView(it).setPositiveButton("DONE",null).show()}
 }
 private fun settings(){
  val choices=arrayOf("Pose guide: "+if(poseOn)"ON" else "OFF","Filters panel","Exposure","Video quality: "+if(quality==Quality.UHD)"4K" else "FHD")
  android.app.AlertDialog.Builder(this).setTitle("LUMO  •  SETTINGS").setItems(choices){_,i->when(i){
   0->{poseOn=!poseOn;pose.visibility=if(poseOn)View.VISIBLE else View.GONE}
   1->{filtersOn=!filtersOn;filterPanel.visibility=if(filtersOn)View.VISIBLE else View.GONE}
   2->exposure()
   3->{quality=if(quality==Quality.FHD)Quality.UHD else Quality.FHD;refresh();startCamera()}
  }}.setNegativeButton("CLOSE",null).show()
 }
 private fun stamp()=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(System.currentTimeMillis())
 private fun takePhoto(){
  val cap=still?:return
  val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"LUMO_"+stamp()+".jpg");put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/LumoCamera")}
  val opts=ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values).build()
  cap.takePicture(opts,executor,object:ImageCapture.OnImageSavedCallback{
   override fun onImageSaved(r:ImageCapture.OutputFileResults){runOnUiThread{shutter.animate().scaleX(.9f).scaleY(.9f).setDuration(90).withEndAction{shutter.animate().scaleX(1f).scaleY(1f).setDuration(130).start()}.start();Toast.makeText(this@MainActivity,"Photo saved",Toast.LENGTH_SHORT).show()}}
   override fun onError(e:ImageCaptureException){runOnUiThread{Toast.makeText(this@MainActivity,e.message?:"Capture failed",Toast.LENGTH_SHORT).show()}}
  })
 }
 private fun recordVideo(){
  if(isRecording){recording?.stop();return}
  val cap=video?:return
  val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"LUMO_"+stamp()+".mp4");put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/LumoCamera")}
  val out=MediaStoreOutputOptions.Builder(contentResolver,MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
  var pending=cap.output.prepareRecording(this,out)
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)pending=pending.withAudioEnabled()
  recording=pending.start(ContextCompat.getMainExecutor(this)){e->when(e){
   is VideoRecordEvent.Start->{isRecording=true;refresh()}
   is VideoRecordEvent.Finalize->{isRecording=false;recording=null;refresh();Toast.makeText(this,if(e.hasError())"Video failed" else "Video saved",Toast.LENGTH_SHORT).show()}
  }}
 }
 override fun onDestroy(){recording?.stop();executor.shutdown();super.onDestroy()}

 private class PoseView(c:android.content.Context):View(c){
  private var lastX=0f
  private var lastY=0f
  init{visibility=View.GONE;isClickable=true}
  var reference:Bitmap?=null
  var imageAlpha=0.42f
  var imageScale=0.72f
  var imageOffsetX=0f
  var imageOffsetY=0f
  override fun onTouchEvent(event:MotionEvent):Boolean{
   when(event.action){
    MotionEvent.ACTION_DOWN->{lastX=event.x;lastY=event.y;return true}
    MotionEvent.ACTION_MOVE->{
     imageOffsetX+=(event.x-lastX)/width.coerceAtLeast(1)
     imageOffsetY+=(event.y-lastY)/height.coerceAtLeast(1)
     lastX=event.x;lastY=event.y;invalidate();return true
    }
    MotionEvent.ACTION_UP->{performClick();return true}
   }
   return true
  }
  override fun performClick():Boolean{super.performClick();return true}
  override fun onDraw(c:Canvas){
   super.onDraw(c)
   val bmp=reference
   if(bmp!=null){
    val maxW=width*imageScale;val maxH=height*imageScale
    val ratio=minOf(maxW/bmp.width,maxH/bmp.height)
    val dw=bmp.width*ratio;val dh=bmp.height*ratio
    val left=(width-dw)/2+imageOffsetX*width
    val top=(height-dh)/2+imageOffsetY*height
    val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{alpha=(imageAlpha*255).toInt()}
    c.drawBitmap(bmp,null,RectF(left,top,left+dw,top+dh),paint)
   }
  }
 }
}