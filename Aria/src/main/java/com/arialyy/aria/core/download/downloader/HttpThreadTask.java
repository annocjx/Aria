/*
 * Copyright (C) 2016 AriaLyy(https://github.com/AriaLyy/Aria)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arialyy.aria.core.download.downloader;

import com.arialyy.aria.core.common.AbsThreadTask;
import com.arialyy.aria.core.common.RequestEnum;
import com.arialyy.aria.core.common.SubThreadConfig;
import com.arialyy.aria.core.config.DownloadConfig;
import com.arialyy.aria.core.download.DTaskWrapper;
import com.arialyy.aria.core.download.DownloadEntity;
import com.arialyy.aria.core.common.http.HttpTaskConfig;
import com.arialyy.aria.exception.AriaIOException;
import com.arialyy.aria.exception.TaskException;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.BufferedRandomAccessFile;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Set;

/**
 * Created by lyy on 2017/1/18. 下载线程
 */
final class HttpThreadTask extends AbsThreadTask<DownloadEntity, DTaskWrapper> {
  private final String TAG = "HttpThreadTask";

  HttpThreadTask(SubThreadConfig<DTaskWrapper> config) {
    super(config);
  }

  @Override public HttpThreadTask call() throws Exception {
    super.call();
    if (mRecord.isComplete) {
      handleComplete();
      return this;
    }
    HttpURLConnection conn = null;
    BufferedInputStream is = null;
    BufferedRandomAccessFile file = null;
    try {
      HttpTaskConfig taskDelegate = getTaskWrapper().asHttp();
      URL url = ConnectionHelp.handleUrl(getConfig().url, taskDelegate);
      conn = ConnectionHelp.handleConnection(url, taskDelegate);
      if (mTaskWrapper.isSupportBP()) {
        ALog.d(TAG,
            String.format("任务【%s】线程__%s__开始下载【开始位置 : %s，结束位置：%s】", getFileName(),
                mRecord.threadId, mRecord.startLocation, mRecord.endLocation));
        conn.setRequestProperty("Range", String.format("bytes=%s-%s", mRecord.startLocation,
            (mRecord.endLocation - 1)));
      } else {
        ALog.w(TAG, "该下载不支持断点");
      }
      ConnectionHelp.setConnectParam(taskDelegate, conn);
      conn.setConnectTimeout(getTaskConfig().getConnectTimeOut());
      conn.setReadTimeout(getTaskConfig().getIOTimeOut());  //设置读取流的等待时间,必须设置该参数
      if (taskDelegate.isChunked()) {
        conn.setDoInput(true);
        conn.setChunkedStreamingMode(0);
      }
      conn.connect();
      // 传递参数
      if (taskDelegate.getRequestEnum() == RequestEnum.POST) {
        Map<String, String> params = taskDelegate.getParams();
        if (params != null) {
          OutputStreamWriter dos = new OutputStreamWriter(conn.getOutputStream());
          Set<String> keys = params.keySet();
          StringBuilder sb = new StringBuilder();
          for (String key : keys) {
            sb.append(key).append("=").append(URLEncoder.encode(params.get(key))).append("&");
          }
          String paramStr = sb.toString();
          paramStr = paramStr.substring(0, paramStr.length() - 1);
          dos.write(paramStr);
          dos.flush();
          dos.close();
        }
      }

      is = new BufferedInputStream(ConnectionHelp.convertInputStream(conn));
      if (taskDelegate.isChunked()) {
        readChunked(is);
      } else if (getConfig().isOpenDynamicFile) {
        readDynamicFile(is);
      } else {
        //创建可设置位置的文件
        file =
            new BufferedRandomAccessFile(getConfig().tempFile, "rwd",
                getTaskConfig().getBuffSize());
        //设置每条线程写入文件的位置
        file.seek(mRecord.startLocation);
        readNormal(is, file);
        handleComplete();
      }
    } catch (MalformedURLException e) {
      fail(mChildCurrentLocation, new TaskException(TAG,
          String.format("任务【%s】下载失败，filePath: %s, url: %s", getFileName(),
              getEntity().getDownloadPath(), getEntity().getUrl()), e));
    } catch (IOException e) {
      fail(mChildCurrentLocation, new TaskException(TAG,
          String.format("任务【%s】下载失败，filePath: %s, url: %s", getFileName(),
              getEntity().getDownloadPath(), getEntity().getUrl()), e));
    } catch (Exception e) {
      fail(mChildCurrentLocation, new TaskException(TAG,
          String.format("任务【%s】下载失败，filePath: %s, url: %s", getFileName(),
              getEntity().getDownloadPath(), getEntity().getUrl()), e));
    } finally {
      try {
        if (file != null) {
          file.close();
        }
        if (is != null) {
          is.close();
        }
        if (conn != null) {
          conn.disconnect();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return this;
  }

  /**
   * 读取chunked数据
   */
  private void readChunked(InputStream is) {
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(getConfig().tempFile, true);
      byte[] buffer = new byte[getTaskConfig().getBuffSize()];
      int len;
      while (isLive() && (len = is.read(buffer)) != -1) {
        if (isBreak()) {
          break;
        }
        if (mSpeedBandUtil != null) {
          mSpeedBandUtil.limitNextBytes(len);
        }
        fos.write(buffer, 0, len);
        progress(len);
      }
      handleComplete();
    } catch (IOException e) {
      fail(mChildCurrentLocation, new AriaIOException(TAG,
          String.format("文件下载失败，savePath: %s, url: %s", getEntity().getDownloadPath(),
              getConfig().url),
          e));
    } finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * 动态长度文件读取方式
   */
  private void readDynamicFile(InputStream is) {
    FileOutputStream fos = null;
    FileChannel foc = null;
    ReadableByteChannel fic = null;
    try {
      int len;
      fos = new FileOutputStream(getConfig().tempFile, true);
      foc = fos.getChannel();
      fic = Channels.newChannel(is);
      ByteBuffer bf = ByteBuffer.allocate(getTaskConfig().getBuffSize());
      //如果要通过 Future 的 cancel 方法取消正在运行的任务，那么该任务必定是可以 对线程中断做出响应 的任务。

      while (isLive() && (len = fic.read(bf)) != -1) {
        if (isBreak()) {
          break;
        }
        if (mSpeedBandUtil != null) {
          mSpeedBandUtil.limitNextBytes(len);
        }
        if (mChildCurrentLocation + len >= mRecord.endLocation) {
          len = (int) (mRecord.endLocation - mChildCurrentLocation);
          bf.flip();
          fos.write(bf.array(), 0, len);
          bf.compact();
          progress(len);
          break;
        } else {
          bf.flip();
          foc.write(bf);
          bf.compact();
          progress(len);
        }
      }
      handleComplete();
    } catch (IOException e) {
      fail(mChildCurrentLocation, new AriaIOException(TAG,
          String.format("文件下载失败，savePath: %s, url: %s", getEntity().getDownloadPath(),
              getConfig().url),
          e));
    } finally {
      try {
        if (fos != null) {
          fos.flush();
          fos.close();
        }
        if (foc != null) {
          foc.close();
        }
        if (fic != null) {
          fic.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * 读取普通的文件流
   */
  private void readNormal(InputStream is, BufferedRandomAccessFile file)
      throws IOException {
    byte[] buffer = new byte[getTaskConfig().getBuffSize()];
    int len;
    while (isLive() && (len = is.read(buffer)) != -1) {
      if (isBreak()) {
        break;
      }
      if (mSpeedBandUtil != null) {
        mSpeedBandUtil.limitNextBytes(len);
      }
      file.write(buffer, 0, len);
      progress(len);
    }
  }

  /**
   * 处理完成配置文件的更新或事件回调
   */
  private void handleComplete() {
    if (isBreak()) {
      return;
    }
    if (!checkBlock()) {
      return;
    }
    if (getTaskWrapper().asHttp().isChunked()) {
      sendCompleteMsg();
      return;
    }

    //支持断点的处理
    if (mTaskWrapper.isSupportBP()) {
      writeConfig(true, mRecord.endLocation);
      sendCompleteMsg();
    } else {
      sendCompleteMsg();
    }
  }

  @Override public int getMaxSpeed() {
    return mAridManager.getDownloadConfig().getMaxSpeed();
  }

  @Override protected DownloadConfig getTaskConfig() {
    return getTaskWrapper().getConfig();
  }
}
