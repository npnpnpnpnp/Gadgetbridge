/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProCatalogueClient;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/** Ten catalogue previews per page; loads the full JSON once and downloads only visible previews. */
public class FitProCatalogueActivity extends AbstractGBActivity {
    private static final int PAGE_SIZE=10;
    private final ExecutorService worker=Executors.newFixedThreadPool(3);
    private GBDevice device; private FitProWatchfaceInfo info; private List<FitProCatalogueClient.Face> faces=new ArrayList<>();
    private GridLayout grid; private TextView pageText, status; private EditText pageInput; private int page;
    @Override protected void onCreate(Bundle state) { super.onCreate(state); device=getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE); info=FitProWatchfaceInstallHandler.readInfo(device); if(device==null||info==null){finish();return;} setTitle(R.string.fitpro_catalogue); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int p=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(p,p,p,p);status=new TextView(this);root.addView(status);LinearLayout controls=new LinearLayout(this);Button previous=new Button(this);previous.setText(R.string.fitpro_previous);previous.setOnClickListener(v->showPage(page-1));controls.addView(previous);pageInput=new EditText(this);pageInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);controls.addView(pageInput,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));Button go=new Button(this);go.setText(R.string.fitpro_go);go.setOnClickListener(v->{try{showPage(Integer.parseInt(pageInput.getText().toString())-1);}catch(Exception ignored){}});controls.addView(go);Button next=new Button(this);next.setText(R.string.fitpro_next);next.setOnClickListener(v->showPage(page+1));controls.addView(next);root.addView(controls);pageText=new TextView(this);root.addView(pageText);ScrollView scroll=new ScrollView(this);grid=new GridLayout(this);grid.setColumnCount(2);scroll.addView(grid);root.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1));setContentView(root);load(); }
    private void load(){status.setText(R.string.fitpro_catalogue_loading);worker.execute(()->{try{faces=FitProCatalogueClient.load(info);runOnUiThread(()->showPage(0));}catch(Exception e){runOnUiThread(()->status.setText(getString(R.string.fitpro_catalogue_failed,e.getMessage())));}});}
    private void showPage(int wanted){int pages=Math.max(1,(faces.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.max(0,Math.min(wanted,pages-1));status.setText(getString(R.string.fitpro_catalogue_count,faces.size()));pageInput.setText(Integer.toString(page+1));pageText.setText(getString(R.string.fitpro_page_of,page+1,pages));grid.post(()->{grid.removeAllViews();for(int i=page*PAGE_SIZE;i<Math.min(faces.size(),(page+1)*PAGE_SIZE);i++)addFace(faces.get(i));});}
    private void addFace(@NonNull FitProCatalogueClient.Face face){FitProPreviewView button=new FitProPreviewView(this);button.setContentDescription(face.title);button.setOnClickListener(v->{Intent intent=new Intent(this,FitProCatalogueDetailActivity.class);intent.putExtra(GBDevice.EXTRA_DEVICE,device);intent.putExtra("face_id",face.id);startActivity(intent);});int margin=(int)(4*getResources().getDisplayMetrics().density), width=(grid.getWidth()-4*margin)/2, height=Math.round(width*286f/240f);GridLayout.LayoutParams params=new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),GridLayout.spec(GridLayout.UNDEFINED));params.width=width;params.height=height;params.setMargins(margin,margin,margin,margin);grid.addView(button,params);if(face.previewUrl!=null)worker.execute(()->{try{byte[] data=FitProCatalogueClient.download(face.previewUrl);runOnUiThread(()->button.setImage(data));}catch(Exception ignored){}});}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
