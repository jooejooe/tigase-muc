/*
 * Tigase Jabber/XMPP Multi-User Chat Component
 * Copyright (C) 2008 "Bartosz M. Małkowski" <bartosz.malkowski@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.muc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tigase.db.TigaseDBException;
import tigase.db.UserNotFoundException;
import tigase.db.UserRepository;
import tigase.form.Field;
import tigase.form.Form;
import tigase.form.Field.FieldType;

/**
 * @author bmalkow
 * 
 */
public class RoomConfig {

	public static enum Anonymity {
		/**
		 * Fully-Anonymous Room -- a room in which the full JIDs or bare JIDs of
		 * occupants cannot be discovered by anyone, including room admins and
		 * room owners; such rooms are NOT RECOMMENDED or explicitly supported
		 * by MUC, but are possible using this protocol if a service
		 * implementation offers the appropriate configuration options; contrast
		 * with Non-Anonymous Room and Semi-Anonymous Room.
		 */
		fullanonymous,
		/**
		 * Non-Anonymous Room -- a room in which an occupant's full JID is
		 * exposed to all other occupants, although the occupant may choose any
		 * desired room nickname; contrast with Semi-Anonymous Room and
		 * Fully-Anonymous Room.
		 */
		nonanonymous,
		/**
		 * Semi-Anonymous Room -- a room in which an occupant's full JID can be
		 * discovered by room admins only; contrast with Fully-Anonymous Room
		 * and Non-Anonymous Room.
		 */
		semianonymous
	}

	public static interface RoomConfigListener {

		/**
		 * @param room
		 *            TODO
		 * @param modifiedVars
		 */
		void onConfigChanged(RoomConfig roomConfig, Set<String> modifiedVars);
	}

	private final static Set<String> CODE_104_ = new HashSet<String>() {
		{
			add("muc#roomconfig_enablelogging");
			add("muc#roomconfig_anonymity");
		}
	};

	public static final String MUC = "muc#";

	protected static String[] asStrinTable(Enum<?>[] values) {
		String[] result = new String[values.length];
		int i = 0;
		for (Enum<?> v : values) {
			result[i++] = v.name();
		}
		return result;
	}

	protected final Set<String> blacklist = new HashSet<String>();

	protected final Form form = new Form("form", null, null);

	private final ArrayList<RoomConfigListener> listeners = new ArrayList<RoomConfigListener>();

	private final String roomId;

	/**
	 * @param roomId
	 */
	public RoomConfig(String roomId) {
		this.roomId = roomId;
		init();
	}

	public void addListener(RoomConfigListener listener) {
		this.listeners.add(listener);
	}

	@Override
	public RoomConfig clone() {
		final RoomConfig rc = new RoomConfig(this.roomId);
		rc.blacklist.addAll(this.blacklist);
		rc.form.copyValuesFrom(form);
		return rc;
	}

	public String[] compareTo(RoomConfig oldConfig) {
		Set<String> result = new HashSet<String>();

		Set<String> vars = equals(oldConfig.form);
		for (String var : vars) {
			if ((MUC + "roomconfig_anonymity").equals(var)) {
				switch (getRoomAnonymity()) {
				case nonanonymous:
					result.add("172");
					break;
				case semianonymous:
					result.add("173");
					break;
				case fullanonymous:
					result.add("174");
					break;
				}
			} else if ((MUC + "roomconfig_enablelogging").equals(var)) {
				result.add(isLoggingEnabled() ? "170" : "171");
			} else {
				result.add("104");

			}
		}

		return result.size() == 0 ? null : result.toArray(new String[] {});
	}

	public void copyFrom(Form configForm) {
		copyFrom(configForm, true);
	}

	/**
	 * @param form2
	 */
	public void copyFrom(Form configForm, boolean fireEvents) {
		final Set<String> modifiedVars = fireEvents ? equals(configForm) : null;
		form.copyValuesFrom(configForm);
		if (modifiedVars != null && modifiedVars.size() > 0) {
			fireConfigChanged(modifiedVars);
		}
	}

	public void copyFrom(RoomConfig c) {
		copyFrom(c.form, true);
	}

	/**
	 * @param defaultRoomConfig
	 * @param b
	 */
	public void copyFrom(RoomConfig c, boolean fireEvents) {
		copyFrom(c.form, fireEvents);
	}

	private Set<String> equals(Form form) {
		final HashSet<String> result = new HashSet<String>();
		/*
		 * for (Field field : this.form.getAllFields()) { Field of =
		 * form.get(field.getVar()); if (of == null) {
		 * result.add(field.getVar()); } else { boolean tmp =
		 * Arrays.equals(field.getValues(), of.getValues()); if (!tmp)
		 * result.add(field.getVar()); } }
		 */
		for (Field field : form.getAllFields()) {
			Field of = this.form.get(field.getVar());
			if (of == null) {
				result.add(field.getVar());
			}
			boolean tmp = Arrays.equals(field.getValues(), of.getValues());
			if (!tmp)
				result.add(field.getVar());
		}

		return result;
	}

	private void fireConfigChanged(final Set<String> modifiedVars) {
		for (RoomConfigListener listener : this.listeners) {
			listener.onConfigChanged(this, modifiedVars);

		}
	}

	public Form getConfigForm() {
		return form;
	}

	public Anonymity getRoomAnonymity() {
		try {
			String tmp = form.getAsString(MUC + "roomconfig_anonymity");
			return tmp == null ? Anonymity.semianonymous : Anonymity.valueOf(tmp);
		} catch (Exception e) {
			return Anonymity.semianonymous;
		}
	}

	public String getRoomDesc() {
		return form.getAsString(MUC + "roomconfig_roomdesc");
	}

	public String getRoomId() {
		return roomId;
	}

	public String getRoomName() {
		return form.getAsString(MUC + "roomconfig_roomname");
	}

	protected void init() {
		form.addField(Field.fieldTextSingle(MUC + "roomconfig_roomname", "", "Natural-Language Room Name"));
		form.addField(Field.fieldTextSingle(MUC + "roomconfig_roomdesc", "", "Short Description of Room"));
		form.addField(Field.fieldBoolean(MUC + "roomconfig_persistentroom", Boolean.FALSE, "Make Room Persistent?"));
		form.addField(Field.fieldBoolean(MUC + "roomconfig_moderatedroom", Boolean.FALSE, "Make Room Moderated?"));
		form.addField(Field.fieldBoolean(MUC + "roomconfig_membersonly", Boolean.FALSE, "Make Room Members Only?"));
		form.addField(Field.fieldListSingle(MUC + "roomconfig_anonymity", Anonymity.semianonymous.name(), "Room anonymity level:",
				new String[] { "Non-Anonymous Room", "Semi-Anonymous Room", "Fully-Anonymous Room" }, new String[] {
						Anonymity.nonanonymous.name(), Anonymity.semianonymous.name(), Anonymity.fullanonymous.name() }));
		form.addField(Field.fieldBoolean(MUC + "roomconfig_changesubject", Boolean.FALSE, "Allow Occupants to Change Subject?"));

	}

	public Boolean isChangeSubject() {
		return form.getAsBoolean(MUC + "roomconfig_changesubject");
	}

	public boolean isLoggingEnabled() {
		Boolean x = form.getAsBoolean(MUC + "roomconfig_enablelogging");
		return x == null ? false : x.booleanValue();
	}

	public Boolean isPersistentRoom() {
		return form.getAsBoolean(MUC + "roomconfig_persistentroom");
	}

	public Boolean isRoomMembersOnly() {
		return form.getAsBoolean(MUC + "roomconfig_membersonly");
	}

	public Boolean isRoomModerated() {
		return form.getAsBoolean(MUC + "roomconfig_moderatedroom");
	}

	public void read(final UserRepository repository, final MucConfig config, final String subnode) throws UserNotFoundException,
			TigaseDBException {
		String[] keys = repository.getKeys(config.getServiceName(), subnode);
		if (keys != null)
			for (String key : keys) {
				String[] values = repository.getDataList(config.getServiceName(), subnode, key);
				setValues(key, values);
			}
	}

	public void removeListener(RoomConfigListener listener) {
		this.listeners.remove(listener);
	}

	private void setValue(String var, Object data) {
		Field f = form.get(var);

		if (f == null) {
			return;
		} else if (data == null) {
			f.setValues(new String[] {});
		} else if (data instanceof String) {
			String str = (String) data;
			if (f.getType() == FieldType.bool && !"0".equals(str) && !"1".equals(str))
				throw new RuntimeException("Boolean fields allows only '1' or '0' values");
			f.setValues(new String[] { str });
		} else if (data instanceof Boolean && f.getType() == FieldType.bool) {
			boolean b = ((Boolean) data).booleanValue();
			f.setValues(new String[] { b ? "1" : "0" });
		} else if (data instanceof String[] && (f.getType() == FieldType.list_multi || f.getType() == FieldType.text_multi)) {
			String[] d = (String[]) data;
			f.setValues(d);
		} else {
			throw new RuntimeException("Cannot match type " + data.getClass().getCanonicalName() + " to field type "
					+ f.getType().name());
		}

	}

	private void setValues(String var, String[] data) {
		if (data == null || data.length > 1) {
			setValue(var, data);
		} else if (data.length == 0) {
			setValue(var, null);
		} else {
			setValue(var, data[0]);
		}
	}

	public void write(final UserRepository repo, final MucConfig config, final String subnode) throws UserNotFoundException,
			TigaseDBException {
		List<Field> fields = form.getAllFields();
		for (Field field : fields) {
			if (field.getVar() != null && !this.blacklist.contains(field.getVar())) {
				String[] values = field.getValues();
				String value = field.getValue();
				if (values == null || values.length == 0) {
					repo.removeData(config.getServiceName(), subnode, field.getVar());
				} else if (values.length == 1) {
					repo.setData(config.getServiceName(), subnode, field.getVar(), value);
				} else {
					repo.setDataList(config.getServiceName(), subnode, field.getVar(), values);
				}
			}
		}
	}

}
