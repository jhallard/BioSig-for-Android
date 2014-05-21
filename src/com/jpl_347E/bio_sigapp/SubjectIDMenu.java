package com.jpl_347E.bio_sigapp; 
  
import android.app.Activity; 
import android.content.Intent; 
import android.os.Bundle; 
import android.view.View; 
import android.view.View.OnClickListener; 
import android.widget.Button; 
import android.widget.EditText; 
  
public class SubjectIDMenu extends Activity { 
    //======================================================================================= 
    // Data Members 
    //======================================================================================= 
    EditText textField; 
    Button updateButton; 
      
    //======================================================================================= 
    // Methods 
    //======================================================================================= 
    protected void onCreate(Bundle savedInstanceState) { 
        super.onCreate(savedInstanceState); 
        setContentView(R.layout.activity_subject_id); 
          
        textField = (EditText)findViewById(R.id.subIDnew); 
        updateButton = (Button)findViewById(R.id.subIDbutton); 
          
        String subID = getIntent().getStringExtra("Subject ID"); 
          
        textField.setText(subID); 
          
          
        updateButton.setOnClickListener(new OnClickListener() { 
            public void onClick(View arg0) { 
                Intent intent = new Intent(); 
                intent.putExtra("Subject ID", textField.getText().toString()); 
                setResult(Activity.RESULT_OK, intent); 
                finish(); 
            } 
              
        }); 
    } 
} 