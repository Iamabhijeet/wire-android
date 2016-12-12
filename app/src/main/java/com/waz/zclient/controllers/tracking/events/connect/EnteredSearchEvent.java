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
package com.waz.zclient.controllers.tracking.events.connect;

import android.support.annotation.NonNull;
import android.text.TextUtils;
import com.waz.zclient.core.controllers.tracking.attributes.Attribute;
import com.waz.zclient.core.controllers.tracking.events.Event;

public class EnteredSearchEvent extends Event {

    public EnteredSearchEvent(boolean isAddingToGroupConversation, String filter) {
        attributes.put(Attribute.CONTEXT, isAddingToGroupConversation ? "add_to_conversation" : "startui");
        boolean byUsername = !TextUtils.isEmpty(filter) && filter.charAt(0) == '@';
        attributes.put(Attribute.BY_USERNAME, String.valueOf(byUsername));
    }

    @NonNull
    @Override
    public String getName() {
        return "connect.entered_search";
    }
}
