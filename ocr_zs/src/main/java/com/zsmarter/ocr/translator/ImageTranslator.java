
/*
 * (C) Copyright 2018, ZSmarter Technology Co, Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.zsmarter.ocr.translator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.zsmarter.ocr.util.ImageUtil;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static com.zsmarter.ocr.camera.CameraActivity.CONTENT_TYPE_BANK_CARD;
import static com.zsmarter.ocr.camera.CameraActivity.CONTENT_TYPE_ID_CARD;

public class ImageTranslator {
    private static final String TAG = "ImageTranslator";
    private static String languageDir = "";
    private static ImageTranslator mImageTranslator = null;
    private static final String TRAINEDDATA_SUFFIX = ".traineddata";
    private String translateResult = "";
    public static final String LANGUAGE_NUM = "num";
    public static final String LANGUAGE_CHINESE = "chi_sim";
    public static final String LANGUAGE_ENG = "eng";

    private String mContentType = "";
    private String mLanguage;

    private int mPageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT;


    private ImageTranslator() {
    }

    public static ImageTranslator getInstance() {
        if (mImageTranslator == null) {
            synchronized (ImageTranslator.class) {
                if (mImageTranslator == null) {
                    mImageTranslator = new ImageTranslator();
                }
            }
        }
        return mImageTranslator;
    }

    public void setContentType(String contentType) {
        mContentType = contentType;
    }

    public void setLanguage(String language) {
        mLanguage = language;
    }

    public void setmPageSegMode(int mPageSegMode) {
        this.mPageSegMode = mPageSegMode;
    }

    public static void initOpenCVLib(){
        OpenCVLoader.initDebug();
    }

    /**
     * Translator Callback
     */
    public interface TesseractCallback {
        void onStart(Bitmap bitmap);

        void onResult(String result);

        void onFail(String reason);
    }

    public void translate(Context context, final String filePath, final TesseractCallback callBack) {
        Bitmap bmp = null;
        try {
            FileInputStream fis = new FileInputStream(filePath);
            bmp = BitmapFactory.decodeStream(fis);
            translate(context, bmp, callBack);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void translate(Context context, Bitmap bmp, final TesseractCallback callBack) {
        checkLanguage(context, mLanguage);
        TessBaseAPI baseApi = new TessBaseAPI();
        baseApi.setDebug(true);
        String tessdataPath = languageDir.substring(0, languageDir.length() - "tessdata/".length());
        Log.d(TAG, "translate: tessdataPath : " + tessdataPath);
        if (baseApi.init(tessdataPath, mLanguage, TessBaseAPI.OEM_CUBE_ONLY)) {
            baseApi.setPageSegMode(mPageSegMode);
            if (bmp != null) {

                Bitmap targetBitmap = imageProcessing(bmp);

                //Callback after image preprocessing
                callBack.onStart(targetBitmap);

                //set the bitmap for TessBaseAPI
                baseApi.setImage(targetBitmap);
                baseApi.setVariable(TessBaseAPI.VAR_SAVE_BLOB_CHOICES, TessBaseAPI.VAR_TRUE);
                String result = baseApi.getUTF8Text();
                baseApi.clear();
                baseApi.end();

                switch (mContentType) {
                    case CONTENT_TYPE_BANK_CARD:
                        if (filterResult2(result)) {
                            callBack.onResult(translateResult);
                        } else {
                            callBack.onFail("ocr translate failed: " + result);
                        }
                        break;
                    case CONTENT_TYPE_ID_CARD:
                        if (filterResultEmpty(result)) {
                            callBack.onResult(translateResult);
                        } else {
                            callBack.onFail("ocr translate failed: " + result);
                        }
                        break;
                    default:
                        if (filterResultEmpty(result)) {
                            callBack.onResult(translateResult);
                        } else {
                            callBack.onFail("ocr translate failed: " + result);
                        }
                        break;
                }

            } else {
                callBack.onFail("bitmap is null");
            }
        } else {
            callBack.onFail("TessBaseAPI init failed");
        }
    }

    /**
     * Using OpenCV to process pictures
     * @param bmp
     * @return
     */
    private Bitmap imageProcessing (Bitmap bmp) {
        Mat srcMat = new Mat();
        Mat targetMat = new Mat();
        Mat srcMat1= new Mat();

        Utils.bitmapToMat(bmp, srcMat);
        Imgproc.cvtColor(srcMat, srcMat1, Imgproc.COLOR_BGRA2GRAY);

        //Top hat transformation
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(2*8+1,2*8+1),new Point(0,0));
        Imgproc.morphologyEx(srcMat1,srcMat,Imgproc.MORPH_BLACKHAT,kernel);

        //Filter
//          Imgproc.medianBlur(targetMat_morp, srcMat, 3);
//          Imgproc.GaussianBlur(targetMat_morp, srcMat, new Size(3,3), 0);
        Imgproc.bilateralFilter(srcMat, srcMat1, 5, 30, 20);

        //thresholding
        Imgproc.threshold(srcMat1, targetMat, 127, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        //Further cropping to remove invalid content when recognizing a single line of text
        if (mPageSegMode == TessBaseAPI.PageSegMode.PSM_SINGLE_LINE) {
            targetMat = ImageUtil.crop(targetMat);
        }

        Bitmap targetBitmap = Bitmap.createBitmap(targetMat.width(),targetMat.height(),Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(targetMat, targetBitmap);

        return targetBitmap;
    }

    private boolean filterResultEmpty(String result) {
        if (!TextUtils.isEmpty(result)) {
            translateResult = result;
            return true;
        }
        return false;
    }

    private boolean filterResult2(String result) {
        Log.d(TAG, "filterResult2: result : " + result);
        if (result != null && (result.contains("\n") || result.contains("\r\n"))) {
            if (result.contains("\n")) {
                Log.d(TAG, "result.contains('n')");
            }
            if (result.contains("\r\n")) {
                Log.d(TAG, "result.contains('rn')");
            }
            String[] strings = result.split("\\n");
            Log.d(TAG, "filterResult2: strings : " + Arrays.toString(strings));
            if (strings != null) {
                for (String str : strings) {
                    if (str != null && str.length() > 19) {
                        if (str.contains("\r")) {
                            Log.d(TAG, "result.contains('r')");
                            str = str.replace("\r", "");
                            Log.d(TAG, "str : " + str);
                        }
                        return filterRow(str);
                    }
                }
            }
            return false;
        } else {
            return filterRow(result);
        }
    }

    private boolean filterRow(String string) {
        String[] nums = string.split(" ");
        Log.d(TAG, "nums : " + Arrays.toString(nums));
        if (nums != null && nums.length >= 2) {
            for (int i = 0; i < nums.length - 1; i++) {
                if (nums[i].length() == 6 && nums[i + 1].length() == 13) {
                    translateResult = nums[i] + nums[i + 1];
                    Log.d(TAG, "translateResult : " + translateResult);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean filterResultForIDCard(String result) {
        Log.d(TAG, "filterResult2: result : " + result);
        if (result != null && (result.contains("\n") || result.contains("\r\n"))) {
            if (result.contains("\n")) {
                Log.d(TAG, "result.contains('n')");
            }
            if (result.contains("\r\n")) {
                Log.d(TAG, "result.contains('rn')");
            }
            String[] strings = result.split("\\n");
            Log.d(TAG, "filterResult2: strings : " + Arrays.toString(strings));
            if (strings != null) {
                for (String str : strings) {
                    if (str != null && str.length() >= 18) {
                        if (str.contains("\r")) {
                            Log.d(TAG, "result.contains('r')");
                            str = str.replace("\r", "");
                            Log.d(TAG, "str : " + str);
                        }
                        return filterRowForIDCard(str);
                    }
                }
            }
            return false;
        } else {
            return filterRowForIDCard(result);
        }
    }

    private boolean filterRowForIDCard(String string) {
        String[] nums = string.split(" ");
        Log.d(TAG, "nums : " + Arrays.toString(nums));
        if (nums != null) {
            for (String num : nums) {
                if (num.length() == 18) {
                    translateResult = num;
                    Log.d(TAG, "translateResult : " + translateResult);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * init Language
     */
    private void checkLanguage(Context context, String language) {
        languageDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).getPath() + "/tessdata/";

        String tessdata = languageDir + language + ".traineddata";

        File file = new File(tessdata);
        if (!file.exists()) {
            copyTraineddata(context, languageDir, tessdata, language);

        }

        if ("chi_sim".equals(language) || "chi_tra".equals(language)) {
            String tessdata_vert = languageDir + language + "_vert.traineddata";

            File file_vert = new File(tessdata_vert);
            if (!file_vert.exists()) {
                copyTraineddata(context, languageDir, tessdata_vert, language + "_vert");
            }
        }
    }

    private void copyTraineddata(Context context, String filePath, String sdCardFile, String language) {
        InputStream inputStream;
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                file.mkdirs();
            }

            if (!file.isDirectory()) {
                file.delete();
                file.mkdirs();
            }
            inputStream = context.getResources().getAssets().open(language + TRAINEDDATA_SUFFIX);
            readInputStream(sdCardFile, inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void readInputStream(String storageFile, InputStream inputStream) {
        File file = new File(storageFile);
        FileOutputStream fos = null;
        try {
            if (!file.exists()) {

                fos = new FileOutputStream(file);

                byte[] buffer = new byte[inputStream.available()];

                int lenght = 0;
                while ((lenght = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, lenght);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) {
                    fos.flush();
                    fos.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }


}
