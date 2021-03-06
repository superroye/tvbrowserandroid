/*
 * TV-Browser for Android
 * Copyright (C) 2013 René Mach (rene@tvbrowser.org)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to use, copy, modify or merge the Software,
 * furthermore to publish and distribute the Software free of charge without modifications and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.tvbrowser.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

public class ChannelLabel extends View {
  private final String mChannelName;
  private String mMeasuredChannelName;
  private int mTextMeasuredWidth;
  private final int mOrderNumber;
  
  private final Bitmap mLogo;

  /** View constructors for XML inflation (used by tools) */
  public ChannelLabel(Context context, AttributeSet attributeSet, int defStyleAttr) {
    super(context, attributeSet, defStyleAttr);
    mChannelName = null;
    mLogo = null;
    mOrderNumber = 0;
  }

  public ChannelLabel(Context context, String channelName, Bitmap logo, int orderNumber) {
    super(context);
    
    mChannelName = channelName;
    mLogo = logo;
    mOrderNumber = orderNumber;
    
    calculateChannelName();
  }
  
  private void calculateChannelName() {
    int textWidth = ProgramTableLayoutConstants.COLUMN_WIDTH - ProgramTableLayoutConstants.PADDING_SIDE * 2;
    
    if(mLogo != null && ProgramTableLayoutConstants.SHOW_LOGO) {
      textWidth -= mLogo.getWidth();
      textWidth -= ProgramTableLayoutConstants.TIME_TITLE_GAP;
    }
    
    String nameText = "";
    
    if(ProgramTableLayoutConstants.SHOW_ORDER_NUMBER) {
      nameText = mOrderNumber + ". ";
    }
    
    if(ProgramTableLayoutConstants.SHOW_NAME || mLogo == null) {
      nameText += mChannelName;
    }
    
    int length = ProgramTableLayoutConstants.CHANNEL_PAINT.breakText(nameText, true, textWidth, null);
    float measured = ProgramTableLayoutConstants.CHANNEL_PAINT.measureText(nameText);
    
    if(length < nameText.length() && measured >= textWidth) {
      mMeasuredChannelName = nameText.substring(0,length);
    }
    else {
      mMeasuredChannelName = nameText;
    }
    
    mTextMeasuredWidth = (int)ProgramTableLayoutConstants.CHANNEL_PAINT.measureText(mMeasuredChannelName);
  }
  
  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    setMeasuredDimension(ProgramTableLayoutConstants.COLUMN_WIDTH + ProgramTableLayoutConstants.GAP, ProgramTableLayoutConstants.CHANNEL_BAR_HEIGHT);
  }
  
  @Override
  protected void onDraw(Canvas canvas) {
    canvas.drawRect(0, 0, getWidth() - ProgramTableLayoutConstants.GAP, getHeight(), ProgramTableLayoutConstants.CHANNEL_BACKGROUND_PAINT);
    canvas.drawLine(getWidth() - ProgramTableLayoutConstants.GAP, 0, getWidth() - ProgramTableLayoutConstants.GAP, getHeight(), ProgramTableLayoutConstants.CHANNEL_LINE_PAINT);
        
    int textWidth = 0;
    int logoWidth = 0;
    
    if(mLogo == null || ProgramTableLayoutConstants.SHOW_NAME || ProgramTableLayoutConstants.SHOW_ORDER_NUMBER) {
      textWidth = mTextMeasuredWidth - ProgramTableLayoutConstants.PADDING_SIDE * 2;
    }
    
    if(mLogo != null && ProgramTableLayoutConstants.SHOW_LOGO) {
      logoWidth = mLogo.getWidth() + ProgramTableLayoutConstants.TIME_TITLE_GAP;
    }
    
    canvas.translate(getWidth()/2 - (textWidth + logoWidth)/2, 0);
    
    if(logoWidth != 0) {
      canvas.save();
      
      canvas.translate(0, getHeight()/2 - mLogo.getHeight()/2);
      
      canvas.drawBitmap(mLogo, 0, 0, null);
      canvas.restore();
      
      canvas.translate(logoWidth, 0);
    }
    
    canvas.translate(0, getHeight()/2 - ProgramTableLayoutConstants.CHANNEL_MAX_FONT_HEIGHT/2);
    
    if(textWidth != 0) {
      canvas.drawText(mMeasuredChannelName, 0, ProgramTableLayoutConstants.CHANNEL_MAX_FONT_HEIGHT - ProgramTableLayoutConstants.CHANNEL_FONT_DESCEND, ProgramTableLayoutConstants.CHANNEL_PAINT);
    }
  }
  
  public void updateNameAndLogo() {
    calculateChannelName();
  }
}