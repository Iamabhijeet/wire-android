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
package com.waz.zclient.core.stores;

import com.waz.zclient.core.stores.api.IZMessagingApiStore;
import com.waz.zclient.core.stores.appentry.IAppEntryStore;
import com.waz.zclient.core.stores.connect.IConnectStore;
import com.waz.zclient.core.stores.conversation.IConversationStore;
import com.waz.zclient.core.stores.draft.IDraftStore;
import com.waz.zclient.core.stores.inappnotification.IInAppNotificationStore;
import com.waz.zclient.core.stores.media.IMediaStore;
import com.waz.zclient.core.stores.network.INetworkStore;
import com.waz.zclient.core.stores.participants.IParticipantsStore;
import com.waz.zclient.core.stores.pickuser.IPickUserStore;
import com.waz.zclient.core.stores.profile.IProfileStore;
import com.waz.zclient.core.stores.singleparticipants.ISingleParticipantStore;

public interface IStoreFactory {
    void tearDown();

    boolean isTornDown();

    /* app entry*/
    IAppEntryStore getAppEntryStore();

    /* managing the conversation list */
    IConversationStore getConversationStore();

    /* managing settings and properties of the user */
    IProfileStore getProfileStore();

    /* managing the pick user view */
    IPickUserStore getPickUserStore();

    /* managing connecting & blocking to users */
    IConnectStore getConnectStore();

    /* managing the participants view (old meta view) */
    IParticipantsStore getParticipantsStore();

    ISingleParticipantStore getSingleParticipantStore();

    /* In App notification store (chathead, knocks) */
    IInAppNotificationStore getInAppNotificationStore();

    /* stores started messages */
    IDraftStore getDraftStore();

    IZMessagingApiStore getZMessagingApiStore();

    INetworkStore getNetworkStore();

    void reset();
}
