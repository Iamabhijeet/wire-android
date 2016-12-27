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

import _root_.com.waz.zclient.utils.LayoutSpec
import android.media.AudioManager
import android.os.Vibrator
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.VideoSendState.{DONT_SEND, SEND}
import com.waz.api.VoiceChannelState._
import com.waz.api._
import com.waz.avs.{VideoPreview, VideoRenderer}
import com.waz.model.VoiceChannelData.ConnectionState
import com.waz.model._
import com.waz.service.call.FlowManagerService.{StateAndReason, UnknownState}
import com.waz.service.call.{CallInfo, CallingService}
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.utils.events.{ClockSignal, Signal}
import com.waz.zclient._
import com.waz.zclient.utils.events.ButtonSignal
import org.threeten.bp.Duration._
import org.threeten.bp.Instant._
import org.threeten.bp.{Duration, Instant}

class CurrentCallController(implicit inj: Injector, cxt: WireContext) extends Injectable { self =>

  val glob = inject[GlobalCallingController]
  import glob._

  private implicit val eventContext = cxt.eventContext

  private var _isV3Call = false
  isV3Call(_isV3Call = _)

  private var _v3ServiceAndCurrentConvId = Option.empty[(CallingService, ConvId)]
  v3ServiceAndCurrentConvId(v => _v3ServiceAndCurrentConvId = Some(v))

  val showOngoingControls = callState.map {
    case OTHER_CALLING | OTHERS_CONNECTED | TRANSFER_CALLING | TRANSFER_READY => false
    case _ => true
  }

  val muted = isV3Call.flatMap {
    case true => v3Call.map(_.muted)
    case _ => currentChannel map (_.muted)
  }

  val videoSendState = isV3Call.flatMap {
    case true => v3Call.map(_.videoSendState)
    case _ => currentChannel map (_.video.videoSendState)
  }

  val flowManager = zms map (_.flowmanager)

  //TODO I don't think v2 needs to keep track of this state, might be able to use FM directly for both
  val captureDevices = isV3Call.flatMap {
    case true => flowManager.flatMap(fm => Signal.future(fm.getVideoCaptureDevices))
    case _ => currentChannel map (_.video.captureDevices)
  }

  //TODO when I have a proper field for front camera, make sure it's always set as the first one
  val currentCaptureDeviceIndex = Signal(0)

  val currentCaptureDevice = captureDevices.zip(currentCaptureDeviceIndex).map {
    case (devices, devIndex) if devices.nonEmpty => Some(devices(devIndex % devices.size))
    case _ => None
  }

  (for {
    isV3 <- isV3Call
    fm <- flowManager
    v2 <- v2Service
    conv <- conversation
    device <- currentCaptureDevice
  } yield (isV3, fm, v2, conv, device)) {
    case (isV3, fm, v2, conv, Some(currentDevice)) =>
      if (isV3) fm.setVideoCaptureDevice(conv.remoteId, currentDevice.id)
      else v2.setVideoCaptureDevice(conv.id, currentDevice.id) //need to be set through service for voice channel state
    case _ =>
  }

  val otherUser = Signal(groupCall, userStorage, convId).flatMap {
    case (isGroupCall, usersStorage, convId) if !isGroupCall =>
      usersStorage.optSignal(UserId(convId.str)) // one-to-one conversation has the same id as the other user, so we can access it directly
    case _ => Signal.const[Option[UserData]](None) //Need a none signal to help with further signals
  }

  val callEstablished = isV3Call.flatMap {
    case true => v3Call.map(_.state == SELF_CONNECTED)
    case _ => currentChannel map (_.deviceState == ConnectionState.Connected)
  }

  val onCallEstablished = callEstablished.onChanged

  val outgoingCall = isV3Call.flatMap {
    case true => v3Call map (_.state == SELF_CALLING)
    case _ => currentChannel map (_.state == SELF_CALLING)
  }

  val duration = {
    def timeSince(est: Option[Instant]) = new ClockSignal(Duration.ofSeconds(1).asScala).map(_ => est.fold2(ZERO, between(_, now)))
    isV3Call.flatMap {
      case true => v3Call.flatMap(c => timeSince(c.estabTime))
      case _ => currentChannel flatMap {
        case ch if ch.deviceState == ConnectionState.Connected => timeSince(ch.tracking.established)
        case _ => Signal.const(ZERO)
      }
    } map { duration =>
      val seconds = ((duration.toMillis / 1000) % 60).toInt
      val minutes = ((duration.toMillis / 1000) / 60).toInt
      f"$minutes%02d:$seconds%02d"
    }
  }

  val subtitleText = (for {
    outgoing <- outgoingCall
    video <- videoCall
    state <- callState
    dur <- duration
  } yield (outgoing, video, state, dur)) map {
    case (true,  true,  SELF_CALLING,                     _)        => cxt.getString(R.string.calling__header__outgoing_video_subtitle)
    case (true,  false, SELF_CALLING,                     _)        => cxt.getString(R.string.calling__header__outgoing_subtitle)
    case (false, true,  OTHER_CALLING,                    _)        => cxt.getString(R.string.calling__header__incoming_subtitle__video)
    case (false, false, OTHER_CALLING | OTHERS_CONNECTED, _)        => cxt.getString(R.string.calling__header__incoming_subtitle)
    case (_,     _,     SELF_JOINING,                     _)        => cxt.getString(R.string.calling__header__joining)
    case (false, false, SELF_CONNECTED,                   duration) => duration
    case _ => ""
  }

  val otherParticipants = currentChannel.zip(selfUser) map {
    case (ch, selfUser) if ch.state == SELF_CONNECTED => for (p <- ch.participants if selfUser.id != p.userId && p.state == ConnectionState.Connected) yield p
    case _ => Vector.empty
  }

  val otherSendingVideo = isV3Call.flatMap {
    case true => v3Call.map(_.videoReceiveState == CallInfo.VideoReceiveState.Started)
    case _ => otherParticipants map {
      case Vector(other) => other.sendsVideo
      case _ => false
    }
  }

  val avsStateAndChangeReason = flowManager.flatMap(_.stateOfReceivedVideo)
  val cameraFailed = flowManager.flatMap(_.cameraFailedSig)

  val stateMessageText = Signal(callState, cameraFailed, avsStateAndChangeReason, conversationName, otherSendingVideo).map { values =>
      verbose(s"$values")
      values match {
        case (SELF_CALLING,   true, _, _, _)                                                                    => Option(cxt.getString(R.string.calling__self_preview_unavailable_long))
        case (SELF_JOINING,   _, _, _, _)                                                                       => Option(cxt.getString(R.string.ongoing__connecting))
        case (SELF_CONNECTED, _, StateAndReason(AvsVideoState.STOPPED, AvsVideoReason.BAD_CONNECTION), _, true) => Option(cxt.getString(R.string.ongoing__poor_connection_message))
        case (SELF_CONNECTED, _, _, otherUserName, false)                                                       => Option(cxt.getString(R.string.ongoing__other_turned_off_video, otherUserName))
        case (SELF_CONNECTED, _, UnknownState, otherUserName, true)                                             => Option(cxt.getString(R.string.ongoing__other_unable_to_send_video, otherUserName))
        case _ => None
      }
  }

  val participantIdsToDisplay = isV3Call.flatMap {
    case true => v3Call.map(_.others.toVector)
    case _ => Signal(otherParticipants, groupCall, callerData, otherUser).map { values =>
      verbose(s"(otherParticipants, groupCall, callerData, otherUser): $values")
      values match {
        case (parts, true, callerData, _) if parts.isEmpty => Vector(callerData.id)
        case (parts, false, _, Some(otherUser)) if parts.isEmpty => Vector(otherUser.id)
        case (parts, _, _, _) => parts.map(_.userId)
        case _ => Vector.empty[UserId]
      }
    }
  }

  val flowId = for {
    zms <- zms
    convId <- convId
    conv <- zms.convsStorage.signal(convId)
    rConvId = conv.remoteId
    userData <- otherUser
  } yield (rConvId, userData.map(_.id))

  def setVideoPreview(view: Option[VideoPreview]): Unit = {
    flowManager.on(Threading.Ui) { fm =>
      verbose(s"Setting VideoPreview on Flowmanager, view: $view")
      fm.setVideoPreview(view.orNull)
    }
  }

  def setVideoView(view: Option[VideoRenderer]): Unit = {
    (for {
      fm <- flowManager
      (rConvId, userId) <- flowId
    } yield (fm, rConvId, userId)).on(Threading.Ui) {
      case (fm, rConvId, userId) =>
        verbose(s"Setting ViewRenderer on Flowmanager, rConvId: $rConvId, userId: $userId, view: $view")
        view.foreach(fm.setVideoView(rConvId, userId, _))
    }
  }

  //Set the following signals to keep track of updates as the following methods rely on their values
  v2ServiceAndCurrentConvId.disableAutowiring()
  videoCall.disableAutowiring()
  muted.disableAutowiring()
  videoSendState.disableAutowiring()
  callEstablished.disableAutowiring()

  def dismissCall(): Unit = {
    verbose(s"dismiss call. isV3?: ${_isV3Call}")
    if (_isV3Call) _v3ServiceAndCurrentConvId.foreach {
      case (cs, id) => cs.endCall(id)
    }
    else v2ServiceAndCurrentConvId.currentValue.foreach {
      case (vcs, id) => vcs.silenceVoiceChannel(id)
    }
  }

  def leaveCall(): Unit = {
    verbose(s"leave call. isV3?: ${_isV3Call}")
    if (_isV3Call) _v3ServiceAndCurrentConvId.foreach {
      case (cs, id) => cs.endCall(id)
    }
    else v2ServiceAndCurrentConvId.currentValue.foreach {
      case (vcs, id) => vcs.leaveVoiceChannel(id)
    }
  }

  def vibrate(): Unit = {
    val audioManager = Option(inject[AudioManager])
    val vibrator = Option(inject[Vibrator])

    val disableRepeat = -1
    (audioManager, vibrator) match {
      case (Some(am), Some(vib)) if am.getRingerMode != AudioManager.RINGER_MODE_SILENT =>
        vib.vibrate(cxt.getResources.getIntArray(R.array.call_control_enter).map(_.toLong), disableRepeat)
      case _ =>
    }
  }

  def toggleMuted(): Unit =
    if (_isV3Call)
      _v3ServiceAndCurrentConvId.foreach(_._1.setCallMuted(!muted.currentValue.getOrElse(false)))
    else
      v2ServiceAndCurrentConvId.currentValue.foreach {
        case (vcs, id) => if (muted.currentValue.getOrElse(false)) vcs.unmuteVoiceChannel(id) else vcs.muteVoiceChannel(id)
      }

  def toggleVideo(): Unit = {
    val state = videoSendState.currentValue.getOrElse(DONT_SEND)
    if (_isV3Call)
      _v3ServiceAndCurrentConvId.foreach {
        case (s, cId) => s.setVideoSendActive(cId, if(state == SEND) false else true)
      }
    else v2ServiceAndCurrentConvId.currentValue.foreach {
      case (vcs, id) => vcs.setVideoSendState(id, if (state == SEND) DONT_SEND else SEND)
    }
  }

  val speakerButton = ButtonSignal(zms.flatMap(_.mediamanager.isSpeakerOn), zms.map(_.mediamanager)) {
    case (mm, isSpeakerSet) => mm.setSpeaker(!isSpeakerSet)
  }

  val isTablet = Signal(LayoutSpec.isTablet(cxt))

  val rightButtonShown = Signal(videoCall, callEstablished, captureDevices, isTablet) map {
    case (true, false, _, _) => false
    case (true, true, captureDevices, _) => captureDevices.size >= 0
    case (false, _, _, isTablet) => !isTablet //Tablets don't have ear-pieces, so you can't switch between speakers
    case _ => false
  }
}
