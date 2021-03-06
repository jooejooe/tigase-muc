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
package tigase.muc.repository;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import tigase.component.exceptions.RepositoryException;
import tigase.db.AbstractDataSourceAwareTestCase;
import tigase.db.DBInitException;
import tigase.db.DataSource;
import tigase.db.DataSourceAware;
import tigase.kernel.core.Kernel;
import tigase.muc.*;
import tigase.xmpp.jid.BareJID;
import tigase.xmpp.jid.JID;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Created by andrzej on 15.10.2016.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class AbstractMucDAOTest<DS extends DataSource> extends AbstractDataSourceAwareTestCase<DS, IMucDAO> {

	protected static JID adminJID = JID.jidInstanceNS(UUID.randomUUID().toString(), "test.local",
													  UUID.randomUUID().toString());
	protected static Date creationDate = null;
	protected static JID creatorJID = JID.jidInstanceNS(UUID.randomUUID().toString(), "test.local",
														UUID.randomUUID().toString());
	protected static String emoji = "\uD83D\uDE97\uD83D\uDCA9\uD83D\uDE21";
	protected static BareJID roomJID = BareJID.bareJIDInstanceNS(UUID.randomUUID().toString(), "muc.test.local");
	protected boolean checkEmoji = true;
	protected IMucDAO dao;
	protected Room.RoomFactory roomFactory;

	@Before
	public void setup() throws RepositoryException, DBInitException, IllegalAccessException, InstantiationException {
		roomFactory = getInstance(Room.RoomFactory.class);
		dao = getDataSourceAware();
	}

	@After
	public void tearDown() {
		dao = null;
	}

	@Test
	public void test1_createRoomWithAffiliation() throws RepositoryException {
		RoomConfig rc = new RoomConfig(roomJID);
		rc.setValue(RoomConfig.MUC_ROOMCONFIG_PERSISTENTROOM_KEY, Boolean.TRUE);
		String roomName = roomJID.getLocalpart();
		if (checkEmoji) {
			roomName = roomName + emoji;
		}
		rc.setValue(RoomConfig.MUC_ROOMCONFIG_ROOMNAME_KEY, roomName);
		creationDate = new Date();
		RoomWithId room = roomFactory.newInstance(null, rc, creationDate, creatorJID.getBareJID());
		room.addAffiliationByJid(creatorJID.getBareJID(), Affiliation.owner);

		Object roomId = dao.createRoom(room);

		assertNotNull(room.getId());
		assertNotNull(roomId);
		assertEquals(roomId, room.getId());

		assertNotNull(dao.getRoom(roomJID));

		Map<BareJID, Affiliation> affiliations = dao.getAffiliations(room);
		assertNotNull(affiliations);
		assertEquals(Affiliation.owner, affiliations.get(creatorJID.getBareJID()));
	}

	@Test
	public void test2_getRoom() throws RepositoryException {
		RoomWithId room = dao.getRoom(roomJID);
		room.setAffiliations(dao.getAffiliations(room));
		assertNotNull(room);
		assertNotNull(room.getId());
		if (checkEmoji) {
			assertTrue(room.getConfig().getRoomName().contains(emoji));
		}
		assertEquals(roomJID, room.getRoomJID());
		assertEquals(creationDate.getTime() / 10, room.getCreationDate().getTime() / 10);
		assertEquals(creatorJID.getBareJID(), room.getCreatorJid());

		assertEquals(Affiliation.owner, room.getAffiliation(creatorJID.getBareJID()));
		assertEquals(1, room.getAffiliations().size());
	}

	@Test
	public void test3_setRoomAffiliation() throws RepositoryException {
		RoomWithId room = dao.getRoom(roomJID);
		room.setAffiliations(dao.getAffiliations(room));
		room.addAffiliationByJid(adminJID.getBareJID(), Affiliation.admin);
		dao.setAffiliation(room, adminJID.getBareJID(), Affiliation.admin);

		Map<BareJID, Affiliation> roomAffiliations = new HashMap<>();
		room.getAffiliations().forEach(jid -> roomAffiliations.put(jid, room.getAffiliation(jid)));

		assertEquals(roomAffiliations, dao.getAffiliations(room));

		room.addAffiliationByJid(adminJID.getBareJID(), Affiliation.none);
		dao.setAffiliation(room, adminJID.getBareJID(), Affiliation.none);
		roomAffiliations.clear();
		room.getAffiliations().forEach(jid -> roomAffiliations.put(jid, room.getAffiliation(jid)));

		assertEquals(roomAffiliations, dao.getAffiliations(room));
	}

	@Test
	public void test4_getRoomJids() throws RepositoryException {
		assertTrue(dao.getRoomsJIDList().contains(roomJID));
	}

	@Test
	public void test5_setRoomSubject() throws RepositoryException {
		RoomWithId room = dao.getRoom(roomJID);
		String subject = UUID.randomUUID().toString();
		if (checkEmoji) {
			subject += emoji;
		}
		Date changeDate = new Date();
		dao.setSubject(room, subject, adminJID.getResource(), changeDate);

		room = dao.getRoom(roomJID);
		assertEquals(subject, room.getSubject());
		assertEquals(adminJID.getResource(), room.getSubjectChangerNick());
		assertEquals(changeDate.getTime() / 10, room.getSubjectChangeDate().getTime() / 10);
	}

	@Test
	public void test6_updateRoomConfig() throws RepositoryException {
		RoomWithId room = dao.getRoom(roomJID);
		RoomConfig roomConfig = room.getConfig();
		assertNull(roomConfig.getMaxUsers());
		roomConfig.setValue(RoomConfig.MUC_ROOMCONFIG_MAXUSERS_KEY, "10");
		String roomName = roomJID.getLocalpart().toUpperCase();
		if (checkEmoji) {
			roomName += emoji;
		}
		roomConfig.setValue(RoomConfig.MUC_ROOMCONFIG_ROOMNAME_KEY, roomName);

		dao.updateRoomConfig(roomConfig);

		room = dao.getRoom(roomJID);

		assertEquals((Integer) 10, room.getConfig().getMaxUsers());
		assertEquals(roomName, room.getConfig().getRoomName());
	}

	@Test
	public void test7_destroyRoom() throws RepositoryException {
		assertTrue(dao.getRoomsJIDList().contains(roomJID));
		dao.destroyRoom(roomJID);
		assertFalse(dao.getRoomsJIDList().contains(roomJID));
		assertNull(dao.getRoom(roomJID));
	}

	@Override
	protected Class<? extends DataSourceAware> getDataSourceAwareIfc() {
		return IMucDAO.class;
	}

	@Override
	protected void registerBeans(Kernel kernel) {
		super.registerBeans(kernel);
		kernel.registerBean(Room.RoomFactoryImpl.class).exec();
		kernel.registerBean(MUCConfig.class).exec();
	}
}
