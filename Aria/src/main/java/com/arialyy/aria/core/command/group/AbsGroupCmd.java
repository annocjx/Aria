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
package com.arialyy.aria.core.command.group;

import android.util.Log;
import com.arialyy.aria.core.command.AbsCmd;
import com.arialyy.aria.core.download.DownloadGroupTaskEntity;
import com.arialyy.aria.core.inf.AbsGroupTask;
import com.arialyy.aria.core.inf.BaseGroupTaskEntity;
import com.arialyy.aria.core.queue.DownloadGroupTaskQueue;
import com.arialyy.aria.util.CommonUtil;

/**
 * Created by AriaL on 2017/6/29.
 * 任务组命令
 */
public abstract class AbsGroupCmd<T extends BaseGroupTaskEntity> extends AbsCmd<T> {
  /**
   * 需要控制的子任务url
   */
  String childUrl;

  AbsGroupTask tempTask;

  /**
   * @param targetName 创建任务的对象名
   */
  AbsGroupCmd(String targetName, T entity) {
    mTargetName = targetName;
    mTaskEntity = entity;
    TAG = CommonUtil.getClassName(this);
    if (entity instanceof DownloadGroupTaskEntity) {
      mQueue = DownloadGroupTaskQueue.getInstance();
      isDownloadCmd = true;
    }
  }

  boolean checkTask() {
    tempTask = (AbsGroupTask) mQueue.getTask(mTaskEntity.getEntity());
    if (tempTask == null || !tempTask.isRunning()) {
      Log.d(TAG, "任务组没有执行，先执行任务组任务才能够执行子任务");
      return false;
    }
    return true;
  }
}
