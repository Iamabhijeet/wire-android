/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.calling.controllers

import com.waz.api.VoiceChannelState.OTHER_CALLING
import com.waz.model.ConvId
import com.waz.service.call.CallingService
import com.waz.zclient._
import com.waz.zclient.common.controllers.{CameraPermission, PermissionsController, RecordAudioPermission}

/**
  * This class is intended to be a relatively small controller that every PermissionsActivity can have access to in order
  * to start and accept calls. This controller requires a PermissionsActivity so that it can request and display the
  * related permissions dialogs, that's why it can't be in the GlobalCallController
  */
class CallPermissionsController(implicit inj: Injector, cxt: WireContext) extends Injectable {

  implicit val eventContext = cxt.eventContext

  val globController = inject[GlobalCallingController]
  val permissionsController = inject[PermissionsController]

  val voiceService = globController.voiceService
  val currentConvAndVoiceService = globController.voiceServiceAndCurrentConvId
  val videoCall = globController.videoCall

  val zms = globController.zms.collect { case Some(v) => v }

  val autoAnswerPreference = zms.flatMap(_.prefs.uiPreferenceBooleanSignal(cxt.getResources.getString(R.string.pref_dev_auto_answer_call_key)).signal)

  val currentChannel = globController.currentChannel.collect { case Some(c) => c }
  val incomingCall = currentChannel.map(_.state).map {
    case OTHER_CALLING => true
    case _ => false
  }

  incomingCall.zip(autoAnswerPreference) {
    case (true, true) => acceptCall()
    case _ =>
  }

  private var isV3Call = false
  globController.isV3Call {
    isV3Call = _
  }

  private var v3Service = Option.empty[CallingService]
  globController.v3Service {
    v3Service = _
  }

  private var convId = Option.empty[ConvId]
  globController.convId {
    convId = _
  }

  def startCall(convId: ConvId, withVideo: Boolean): Unit = {
    permissionsController.requiring(if (withVideo) Set(CameraPermission, RecordAudioPermission) else Set(RecordAudioPermission)) {
      if (isV3Call)
        v3Service.foreach(_.startCall(convId, withVideo))
      else
        voiceService.currentValue.foreach(_.foreach(_.joinVoiceChannel(convId, withVideo)))

    }(R.string.calling__cannot_start__title,
      if (withVideo) R.string.calling__cannot_start__no_video_permission__message else R.string.calling__cannot_start__no_permission__message)
  }

  def acceptCall(): Unit = {
    //TODO handle permissions for v3
    if (isV3Call) {
      (v3Service, convId) match {
        case (Some(s), Some(cId)) => s.acceptCall(cId)
        case _ =>
      }
    } else {
      (videoCall.currentValue.getOrElse(false), currentConvAndVoiceService.currentValue.getOrElse(None)) match {
        case (withVideo, Some((vcs, id))) =>
          permissionsController.requiring(if (withVideo) Set(CameraPermission, RecordAudioPermission) else Set(RecordAudioPermission)) {
            vcs.joinVoiceChannel(id, withVideo)
          }(R.string.calling__cannot_start__title,
            if (withVideo) R.string.calling__cannot_start__no_video_permission__message else R.string.calling__cannot_start__no_permission__message,
            vcs.silenceVoiceChannel(id))
        case _ =>
      }
    }
  }
}
