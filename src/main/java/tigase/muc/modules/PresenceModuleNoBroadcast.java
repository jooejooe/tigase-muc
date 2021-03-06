/**
 * Tigase MUC - Multi User Chat component for Tigase
 * Copyright (C) 2007 Tigase, Inc. (office@tigase.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.muc.modules;

import tigase.criteria.Criteria;
import tigase.criteria.ElementCriteria;
import tigase.kernel.beans.Bean;
import tigase.kernel.beans.Inject;
import tigase.muc.*;
import tigase.muc.exceptions.MUCException;
import tigase.muc.history.HistoryProvider;
import tigase.muc.repository.IMucRepository;
import tigase.util.stringprep.TigaseStringprepException;
import tigase.xml.Element;
import tigase.xmpp.jid.JID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class for MucPresenceModule that strips down generated presence stanzas to bare minimum - only sends back presence to
 * user that joined the room for compatibility reasons.
 *
 * @author wojtek
 */
@Bean(name = PresenceModuleNoBroadcast.ID, active = true)
public class PresenceModuleNoBroadcast
		extends PresenceModuleImpl {

	protected static final Logger log = Logger.getLogger(PresenceModuleNoBroadcast.class.getName());
	private static final Criteria CRIT = ElementCriteria.name("presence");
	@Inject
	private MUCConfig config;

	@Inject
	private Ghostbuster2 ghostbuster;

	@Inject
	private HistoryProvider historyProvider;

	@Inject
	private IMucRepository repository;

	@Override
	public void doQuit(final Room room, final JID senderJID) throws TigaseStringprepException {
		final String leavingNickname = room.getOccupantsNickname(senderJID);
		final Affiliation leavingAffiliation = room.getAffiliation(leavingNickname);
		final Role leavingRole = room.getRole(leavingNickname);
		Element presenceElement = new Element("presence");

		presenceElement.setAttribute("type", "unavailable");

		Collection<JID> occupantJIDs = new ArrayList<JID>(room.getOccupantsJidsByNickname(leavingNickname));
		room.removeOccupant(senderJID);
		ghostbuster.remove(senderJID, room);

		room.updatePresenceByJid(senderJID, leavingNickname, null);

		if (config.isMultiItemMode()) {
			final PresenceWrapper selfPresence = PresenceWrapper.preparePresenceW(room, senderJID, presenceElement,
																				  senderJID.getBareJID(), occupantJIDs,
																				  leavingNickname, leavingAffiliation,
																				  leavingRole);
			write(selfPresence.getPacket());
		} else {
			Collection<JID> z = new ArrayList<JID>(1);
			z.add(senderJID);

			final PresenceWrapper selfPresence = PresenceWrapper.preparePresenceW(room, senderJID, presenceElement,
																				  senderJID.getBareJID(), z,
																				  leavingNickname, leavingAffiliation,
																				  leavingRole);
			write(selfPresence.getPacket());
		}

		if (room.getOccupantsCount() == 0) {
			if ((historyProvider != null) && !room.getConfig().isPersistentRoom()) {
				if (log.isLoggable(Level.FINE)) {
					log.fine("Removing history of room " + room.getRoomJID());
				}
				historyProvider.removeHistory(room);
			} else if (log.isLoggable(Level.FINE)) {
				log.fine("Cannot remove history of room " + room.getRoomJID() +
								 " because history provider is not available.");
			}
			repository.leaveRoom(room);

			Element emptyRoomEvent = new Element("EmptyRoom", new String[]{"xmlns"}, new String[]{"tigase:events:muc"});
			emptyRoomEvent.addChild(new Element("room", room.getRoomJID().toString()));
			fireEvent(emptyRoomEvent);
		}
	}

	@Override
	public String[] getFeatures() {
		return null;
	}

	@Override
	public Criteria getModuleCriteria() {
		return CRIT;
	}

	@Override
	public void sendPresencesToNewOccupant(Room room, JID senderJID) throws TigaseStringprepException {
		// do nothing
	}

	@Override
	protected void processExit(Room room, Element presenceElement, JID senderJID)
			throws MUCException, TigaseStringprepException {
		super.processExit(room, presenceElement, senderJID);
	}

	@Override
	protected void sendPresenceToAllOccupants(final Element $presence, Room room, JID senderJID, boolean newRoomCreated,
											  String newNickName) throws TigaseStringprepException {

		// send presence only back to the joining user
		PresenceWrapper presence = super.preparePresence(senderJID, $presence.clone(), room, senderJID, newRoomCreated,
														 newNickName);
		write(presence.getPacket());

	}

}
