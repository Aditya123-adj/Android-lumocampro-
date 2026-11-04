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
 private var portraitMode=false
 private var portraitBlur=55
 private var portraitStyle="Natural"
 private var selectedMode="PHOTO"
 private lateinit var modeStrip:LinearLayout
 private lateinit var portraitPanel:LinearLayout
 private lateinit var posePanel:LinearLayout
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
 private fun roundControl(symbol:String, size:Float=21f, fill:Int=0x55000000, action:()->Unit)=txt(symbol,size,Color.WHITE,true).apply{
  background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(fill);setStroke(d(1f),0x55FFFFFF)}
  isClickable=true;isFocusable=true;setOnClickListener{action()}
  elevation=d(3f).toFloat()
 }
 private fun capsule()=GradientDrawable().apply{cornerRadius=d(30f).toFloat();setColor(0xBFC9C9C9.toInt());setStroke(d(1f),0x99FFFFFF.toInt())}
 private fun makeUi(){
  val root=FrameLayout(this).apply{setBackgroundColor(0xFF777777.toInt())}
  preview=PreviewView(this).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE}
  root.addView(preview,FrameLayout.LayoutParams(-1,-1))
  pose=PoseView(this);root.addView(pose,FrameLayout.LayoutParams(-1,-1))
  preview.setOnTouchListener{_,e->if(e.action==MotionEvent.ACTION_UP){
   val point=preview.meteringPointFactory.createPoint(e.x,e.y)
   camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
   val ring=View(this).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setStroke(d(1.5f),Color.WHITE)}}
   val lp=FrameLayout.LayoutParams(d(54f),d(54f));lp.leftMargin=e.x.toInt()-d(27f);lp.topMargin=e.y.toInt()-d(27f)
   root.addView(ring,lp);ring.animate().scaleX(1.2f).scaleY(1.2f).alpha(0f).setDuration(650).withEndAction{root.removeView(ring)}.start()
  };true}

  val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(d(14f),0,d(14f),0)}
  top.addView(roundControl("×",23f,0x99BFC0C0.toInt()){Toast.makeText(this,"Camera view",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(d(42f),d(42f)))
  top.addView(txt("Pose Camera",14f,Color.WHITE,true).apply{setPadding(d(12f),0,0,0)})
  top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
  top.addView(roundControl("⌕",21f,0x99BFC0C0.toInt()){settings()},LinearLayout.LayoutParams(d(42f),d(42f)))
  top.addView(roundControl("⋮",21f,0x99BFC0C0.toInt()){settings()},LinearLayout.LayoutParams(d(42f),d(42f)).apply{leftMargin=d(7f)})
  root.addView(top,FrameLayout.LayoutParams(-1,d(54f),Gravity.TOP).apply{topMargin=d(35f)})

  val quick=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(d(8f),d(5f),d(8f),d(5f));background=capsule()}
  quick.addView(txt("ϟ",17f,Color.WHITE,true).apply{setPadding(d(12f),d(5f),d(12f),d(5f));setOnClickListener{Toast.makeText(this@MainActivity,"Flash setting",Toast.LENGTH_SHORT).show()}})
  quick.addView(txt("▦",16f,Color.WHITE,true).apply{setPadding(d(12f),d(5f),d(12f),d(5f));setOnClickListener{Toast.makeText(this@MainActivity,"Grid settings",Toast.LENGTH_SHORT).show()}})
  root.addView(quick,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply{leftMargin=d(13f);topMargin=d(101f)})
  root.addView(roundControl("↻",20f,0x99BFC0C0.toInt()){settings()},FrameLayout.LayoutParams(d(42f),d(42f),Gravity.TOP or Gravity.RIGHT).apply{rightMargin=d(14f);topMargin=d(101f)})

  filterPanel=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;visibility=View.GONE;setPadding(d(8f),d(5f),d(8f),d(5f));background=capsule()}
  listOf("Original","Natural","Soft","Vivid","Warm","Cool","Dramatic","Mono").forEach{n->
   filterPanel.addView(txt(n,10f,Color.WHITE,true).apply{setPadding(d(9f),d(8f),d(9f),d(8f));setOnClickListener{portraitStyle=n;filterPanel.childrenSetSelected(this);Toast.makeText(this@MainActivity,"$n style selected",Toast.LENGTH_SHORT).show()}})
  }
  root.addView(filterPanel,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=d(260f)})

  portraitPanel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
   setPadding(d(14f),d(7f),d(14f),d(5f));background=capsule();visibility=View.GONE
  }
  val blurLabel=txt("PORTRAIT BLUR  •  $portraitBlur%",10f,Color.WHITE,true)
  portraitPanel.addView(blurLabel)
  val blurSeek=SeekBar(this).apply{max=100;progress=portraitBlur}
  portraitPanel.addView(blurSeek,LinearLayout.LayoutParams(d(240f),d(28f)))
  blurSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){portraitBlur=v;blurLabel.text="PORTRAIT BLUR  •  $v%"}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){}
  })
  root.addView(portraitPanel,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=d(215f)})

  posePanel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
   setPadding(d(12f),d(8f),d(12f),d(8f));background=capsule();visibility=View.GONE
  }
  val poseActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
  poseActions.addView(chip("＋ Add pose",true).apply{setOnClickListener{posePicker.launch("image/*")}})
  listOf("♙","♟","♙","♟").forEach{symbol->poseActions.addView(txt(symbol,19f,Color.WHITE,true).apply{setPadding(d(10f),d(6f),d(10f),d(6f))})}
  posePanel.addView(poseActions)
  val alphaLabel=txt("OPACITY",9f,Color.WHITE,true);posePanel.addView(alphaLabel)
  val alphaControl=SeekBar(this).apply{max=100;progress=(poseAlpha*100).toInt()}
  posePanel.addView(alphaControl)
  alphaControl.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){poseAlpha=v/100f;pose.imageAlpha=poseAlpha;pose.invalidate()}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){}
  })
  val sizeLabel=txt("SIZE",9f,Color.WHITE,true);posePanel.addView(sizeLabel)
  val sizeControl=SeekBar(this).apply{max=100;progress=((poseScale-.25f)/1.25f*100).toInt().coerceIn(0,100)}
  posePanel.addView(sizeControl)
  sizeControl.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){poseScale=.25f+1.25f*v/100f;pose.imageScale=poseScale;pose.invalidate()}
   override fun onStartTrackingTouch(s:SeekBar?){}
   override fun onStopTrackingTouch(s:SeekBar?){}
  })

  val lower=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(d(10f),d(9f),d(10f),d(8f));background=GradientDrawable().apply{cornerRadius=d(32f).toFloat();setColor(0x66333333)}}
  val zooms=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(d(10f),d(4f),d(10f),d(4f));background=capsule()}
  listOf("0.6×" to .6f,"1×" to 1f,"2×" to 2f,"3×" to 3f).forEach{item->zooms.addView(txt(item.first,12f,if(item.second==1f)0xFFFFD66D.toInt() else Color.WHITE,true).apply{setPadding(d(12f),d(7f),d(12f),d(7f));setOnClickListener{setZoom(item.second)}})}
  lower.addView(zooms)

  modeStrip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  val modesScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(modeStrip)}
  val modeCapsule=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=capsule();setPadding(d(4f),d(3f),d(4f),d(3f))}
  modeCapsule.addView(modesScroll,LinearLayout.LayoutParams(0,d(43f),1f))
  modeCapsule.addView(txt("＋",21f,Color.WHITE,true).apply{setPadding(d(13f),d(5f),d(13f),d(5f));setOnClickListener{showAddModes()}},LinearLayout.LayoutParams(d(48f),d(43f)))
  lower.addView(modeCapsule,LinearLayout.LayoutParams(-1,d(49f)).apply{topMargin=d(8f)})
  rebuildModes()

  val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(d(8f),d(8f),d(8f),d(4f))}
  controls.addView(roundControl("▣",22f,0x99BFC0C0.toInt()){runCatching{startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"image/*"))}},LinearLayout.LayoutParams(d(52f),d(52f)).apply{rightMargin=d(30f)})
  shutter=txt("",18f,Color.BLACK,true).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.WHITE);setStroke(d(4f),0xFFE7E7E7.toInt())};elevation=d(4f).toFloat();setOnClickListener{if(videoMode)recordVideo()else takePhoto()}}
  controls.addView(shutter,LinearLayout.LayoutParams(d(78f),d(78f)))
  controls.addView(roundControl("⟳",27f,0x99BFC0C0.toInt()){front=!front;startCamera()},LinearLayout.LayoutParams(d(52f),d(52f)).apply{leftMargin=d(30f)})
  lower.addView(controls)
  qualityText=txt("PHOTO  •  MAX QUALITY",9f,Color.WHITE,true).apply{setPadding(0,d(2f),0,d(2f));setOnClickListener{quality=if(quality==Quality.FHD)Quality.UHD else Quality.FHD;refresh();startCamera()}}
  lower.addView(qualityText)
  status=txt("TAP TO FOCUS",9f,0xFFECECEC.toInt(),true).apply{setPadding(0,d(2f),0,d(3f))}
  lower.addView(status)
  root.addView(lower,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=d(9f);rightMargin=d(9f);bottomMargin=d(8f)})
  setContentView(root)
  lower.translationY=d(35f).toFloat();lower.alpha=0f
  lower.animate().translationY(0f).alpha(1f).setDuration(430).setInterpolator(DecelerateInterpolator()).start()
 }
 private fun rebuildModes(){
  modeStrip.removeAllViews()
  val labels=listOf("VIDEO","PHOTO","MY POSE","PORTRAIT","PRO","NIGHT","PANO","SLO-MO","TIME-LAPSE")
  val itemWidth=((resources.displayMetrics.widthPixels/resources.displayMetrics.density).toInt()-88)/3
  labels.forEach{label->
   val active=selectedMode==label
   val item=txt(label,10f,if(active)0xFF514A35.toInt() else Color.WHITE,true).apply{
    background=if(active)GradientDrawable().apply{cornerRadius=d(22f).toFloat();setColor(0xFFF5E6B8.toInt())} else null
    setPadding(d(5f),d(9f),d(5f),d(9f))
    setOnClickListener{selectMode(label)}
   }
   modeStrip.addView(item,LinearLayout.LayoutParams(d(itemWidth.toFloat()),d(37f)))
  }
 }
 private fun selectMode(label:String){
  selectedMode=label
  when(label){
   "MY POSE"->{poseOn=true;pose.visibility=View.VISIBLE;posePanel.visibility=View.VISIBLE;portraitPanel.visibility=View.GONE;filterPanel.visibility=View.GONE;rebuildModes();refresh();return}
   "VIDEO","SLO-MO","TIME-LAPSE"->{videoMode=true;portraitMode=false}
   "PORTRAIT"->{videoMode=false;portraitMode=true;filtersOn=true;posePanel.visibility=View.GONE;filterPanel.visibility=View.VISIBLE;portraitPanel.visibility=View.VISIBLE}
   else->{videoMode=false;portraitMode=false;posePanel.visibility=View.GONE;portraitPanel.visibility=View.GONE;filterPanel.visibility=View.GONE}
  }
  rebuildModes();refresh()
 }
 private fun showAddModes(){
  val all=arrayOf("Video","Photo","My Pose","Portrait","Pro Manual","Night","Panorama","Slow Motion","Time-lapse")
  android.app.AlertDialog.Builder(this).setTitle("Add / choose mode").setItems(all){_,i->
   val key=listOf("VIDEO","PHOTO","MY POSE","PORTRAIT","PRO","NIGHT","PANO","SLO-MO","TIME-LAPSE")[i]
   if(!listOf("VIDEO","PHOTO","MY POSE").contains(key)){
    Toast.makeText(this,"Mode selected in the swipe strip",Toast.LENGTH_SHORT).show()
   }
   selectMode(key)
   val target=modeStrip.getChildAt(listOf("VIDEO","PHOTO","MY POSE","PORTRAIT","PRO","NIGHT","PANO","SLO-MO","TIME-LAPSE").indexOf(key))
   if(target!=null) (modeStrip.parent as? HorizontalScrollView)?.smoothScrollTo(target.left,0)
  }.show()
 }
 private fun showPortraitControls(){portraitPanel.visibility=View.VISIBLE;filterPanel.visibility=View.VISIBLE}

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
  if(::modeStrip.isInitialized)rebuildModes()
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