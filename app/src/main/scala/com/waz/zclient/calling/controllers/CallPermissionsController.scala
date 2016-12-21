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

  private implicit val eventContext = cxt.eventContext

  val globController = inject[GlobalCallingController]
  import globController._

  val permissionsController = inject[PermissionsController]

  val autoAnswerPreference = zms.flatMap(_.prefs.uiPreferenceBooleanSignal(cxt.getResources.getString(R.string.pref_dev_auto_answer_call_key)).signal)

  val incomingCall = stateMap.map {
    case CallStateMap.IncomingCall => true
    case _ => false
  }

  incomingCall.zip(autoAnswerPreference) {
    case (true, true) => acceptCall()
    case _ =>
  }

  private var _isV3Call = false
  isV3Call(_isV3Call = _)

  private var _v3Service = Option.empty[CallingService]
  v3Service(s => _v3Service = Some(s))

  private var _convId = Option.empty[ConvId]
  convId (c => _convId = Some(c))

  def startCall(convId: ConvId, withVideo: Boolean): Unit = {
    permissionsController.requiring(if (withVideo) Set(CameraPermission, RecordAudioPermission) else Set(RecordAudioPermission)) {
      if (_isV3Call)
        _v3Service.foreach(_.startCall(convId, withVideo))
      else
        voiceService.currentValue.foreach(_.joinVoiceChannel(convId, withVideo))

    }(R.string.calling__cannot_start__title,
      if (withVideo) R.string.calling__cannot_start__no_video_permission__message else R.string.calling__cannot_start__no_permission__message)
  }

  def acceptCall(): Unit = {
    //TODO handle permissions for v3
    if (_isV3Call) {
      (_v3Service, _convId) match {
        case (Some(s), Some(cId)) => s.acceptCall(cId)
        case _ =>
      }
    } else {
      (videoCall.currentValue.getOrElse(false), voiceServiceAndCurrentConvId.currentValue) match {
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
