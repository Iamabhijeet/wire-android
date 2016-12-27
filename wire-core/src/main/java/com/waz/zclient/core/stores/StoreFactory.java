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
import com.waz.zclient.core.stores.network.INetworkStore;
import com.waz.zclient.core.stores.participants.IParticipantsStore;
import com.waz.zclient.core.stores.pickuser.IPickUserStore;
import com.waz.zclient.core.stores.profile.IProfileStore;
import com.waz.zclient.core.stores.singleparticipants.ISingleParticipantStore;

public abstract class StoreFactory implements IStoreFactory {

    /*
        Lazy loaded stores
     */
    protected IConversationStore conversationStore;
    protected IPickUserStore pickUserStore;
    protected IProfileStore profileStore;
    protected IParticipantsStore participantsStore;
    protected ISingleParticipantStore singleParticipantStore;
    protected IInAppNotificationStore inAppNotificationStore;
    protected IAppEntryStore appEntryStore;
    protected IConnectStore connectStore;
    protected IDraftStore draftStore;
    protected IZMessagingApiStore zMessagingApiStore;
    protected INetworkStore networkStore;
    private boolean isTornDown;

    protected abstract IZMessagingApiStore createZMessagingApiStore();

    protected abstract IAppEntryStore createAppEntryStore();

    protected abstract IConversationStore createConversationStore();

    protected abstract IProfileStore createProfileStore();

    protected abstract IPickUserStore createPickUserStore();

    protected abstract IParticipantsStore createParticipantsStore();

    protected abstract ISingleParticipantStore createSingleParticipantStore();

    protected abstract IInAppNotificationStore createInAppNotificationStore();

    protected abstract IConnectStore createConnectStore();

    protected abstract IDraftStore createDraftStore();

    protected abstract INetworkStore createNetworkStore();

    public StoreFactory() {
        this.isTornDown = false;
    }

    @Override
    public IZMessagingApiStore getZMessagingApiStore() {
        verifyLifecycle();
        if (zMessagingApiStore == null) {
            zMessagingApiStore = createZMessagingApiStore();
        }
        return zMessagingApiStore;
    }

    @Override
    public IAppEntryStore getAppEntryStore() {
        verifyLifecycle();
        if (appEntryStore == null) {
            appEntryStore = createAppEntryStore();
        }

        return appEntryStore;
    }

    @Override
    public IConversationStore getConversationStore() {
        verifyLifecycle();
        if (conversationStore == null) {
            conversationStore = createConversationStore();
        }

        return conversationStore;
    }

    @Override
    public IProfileStore getProfileStore() {
        verifyLifecycle();
        if (profileStore == null) {
            profileStore = createProfileStore();
        }

        return profileStore;
    }

    @Override
    public IPickUserStore getPickUserStore() {
        verifyLifecycle();
        if (pickUserStore == null) {
            pickUserStore = createPickUserStore();
        }

        return pickUserStore;
    }

    @Override
    public IConnectStore getConnectStore() {
        verifyLifecycle();
        if (connectStore == null) {
            connectStore = createConnectStore();
        }

        return connectStore;
    }

    @Override
    public IParticipantsStore getParticipantsStore() {
        verifyLifecycle();
        if (participantsStore == null) {
            participantsStore = createParticipantsStore();
        }

        return participantsStore;
    }

    @Override
    public ISingleParticipantStore getSingleParticipantStore() {
        verifyLifecycle();
        if (singleParticipantStore == null) {
            singleParticipantStore = createSingleParticipantStore();
        }

        return singleParticipantStore;
    }

    @Override
    public IInAppNotificationStore getInAppNotificationStore() {
        verifyLifecycle();
        if (inAppNotificationStore == null) {
            inAppNotificationStore = createInAppNotificationStore();
        }
        return inAppNotificationStore;
    }

    @Override
    public IDraftStore getDraftStore() {
        verifyLifecycle();
        if (draftStore == null) {
            draftStore = createDraftStore();
        }

        return draftStore;
    }

    @Override
    public INetworkStore getNetworkStore() {
        verifyLifecycle();
        if (networkStore == null) {
            networkStore = createNetworkStore();
        }
        return networkStore;
    }

    @Override
    public void reset() {
        if (conversationStore != null) {
            conversationStore.tearDown();
            conversationStore = null;
        }

        if (pickUserStore != null) {
            pickUserStore.tearDown();
            pickUserStore = null;
        }

        if (profileStore != null) {
            profileStore.tearDown();
            profileStore = null;
        }

        if (participantsStore != null) {
            participantsStore.tearDown();
            participantsStore = null;
        }

        if (inAppNotificationStore != null) {
            inAppNotificationStore.tearDown();
            inAppNotificationStore = null;
        }

        if (appEntryStore != null) {
            appEntryStore.tearDown();
            appEntryStore = null;
        }

        if (singleParticipantStore != null) {
            singleParticipantStore.tearDown();
            singleParticipantStore = null;
        }

        if (draftStore != null) {
            draftStore.tearDown();
            draftStore = null;
        }

        if (networkStore != null) {
            networkStore.tearDown();
            networkStore = null;
        }

        isTornDown = false;
    }

    @Override
    public void tearDown() {
        reset();
        if (zMessagingApiStore != null) {
            zMessagingApiStore.tearDown();
            zMessagingApiStore = null;
        }
        this.isTornDown = true;
    }

    @Override
    public boolean isTornDown() {
        return isTornDown;
    }

    private void verifyLifecycle() {
        if (isTornDown) {
            throw new IllegalStateException("StoreFactory is already torn down");
        }
    }
}
