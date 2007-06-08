/*
 * tigase-muc Copyright (C) 2007 by Bartosz M. Małkowski
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program; if not,
 * write to the Free Software Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * 
 * $Id:MUCService.java 43 2007-05-31 07:35:05Z bmalkow $
 */
package tigase.muc;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import tigase.conf.Configurable;
import tigase.db.RepositoryFactory;
import tigase.db.TigaseDBException;
import tigase.db.UserExistsException;
import tigase.db.UserNotFoundException;
import tigase.db.UserRepository;
import tigase.disco.ServiceEntity;
import tigase.disco.ServiceIdentity;
import tigase.disco.XMPPService;
import tigase.muc.xmpp.JID;
import tigase.muc.xmpp.stanzas.IQ;
import tigase.muc.xmpp.stanzas.Message;
import tigase.muc.xmpp.stanzas.Presence;
import tigase.server.AbstractMessageReceiver;
import tigase.server.Packet;
import tigase.util.DNSResolver;
import tigase.util.JIDUtils;
import tigase.xml.Element;

/**
 * Implements MUC service for tigase server.
 * <p>
 * Created: 2007-01-24 13:02:15
 * </p>
 * 
 * @author bmalkow
 * @version $Rev:43 $
 */
public class MUCService extends AbstractMessageReceiver implements XMPPService, Configurable, RoomListener {

    private final static String LETTERS_TO_UNIQUE_NAME = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String MUC_REPO_CLASS_PROP_KEY = "muc-repo-class";

    private static final String MUC_REPO_CLASS_PROP_VAL = "tigase.db.xml.XMLRepository";

    private static final String MUC_REPO_URL_PROP_KEY = "muc-repo-url";

    private static final String MUC_REPO_URL_PROP_VAL = "muc-repository.xml";

    private static Random random = new SecureRandom();

    static Element errorPresence(JID from, JID to, String type, String code, String errorElement) {
        Element p = new Element("presence");
        p.setAttribute("from", from.toString());
        p.setAttribute("to", to.toString());
        p.setAttribute("type", "error");

        Element error = new Element("error");
        error.setAttribute("code", code);
        error.setAttribute("type", type);
        error.addChild(new Element(errorElement, new String[] { "xmlns" },
                new String[] { "urn:ietf:params:xml:ns:xmpp-stanzas" }));
        p.addChild(error);

        return p;
    }

    private static String generateUniqueName() {
        String result = "";
        for (int i = 0; i < 32; i++) {
            result += LETTERS_TO_UNIQUE_NAME.charAt(random.nextInt(LETTERS_TO_UNIQUE_NAME.length()));
        }
        return result;
    }

    private Set<String> allRooms = new HashSet<String>();;

    private Logger log = Logger.getLogger(this.getClass().getName());

    private UserRepository mucRepository;

    private final Map<String, Room> rooms = new HashMap<String, Room>();

    private ServiceEntity serviceEntity = null;

    /**
     * Construct MUC service.
     */
    public MUCService() {
        log.info("Creating tigase-muc ver." + MucVersion.getVersion() + " Service.");
    }

    private void configRoomDiscovery(final RoomConfiguration config) {
        ServiceEntity x = new ServiceEntity(config.getId(), config.getId(), config.getRoomconfigRoomname());
        x.addIdentities(new ServiceIdentity("conference", "text", config.getRoomconfigRoomname()));

        x.addFeatures("http://jabber.org/protocol/muc");
        if (config.isRoomconfigPasswordProtectedRoom()) {
            x.addFeatures("muc_passwordprotected");
        } else {
            x.addFeatures("muc_unsecured");
        }
        if (config.isRoomconfigPersistentRoom()) {
            x.addFeatures("muc_persistent");
        } else {
            x.addFeatures("muc_temporary");
        }
        if (config.isRoomconfigMembersOnly()) {
            x.addFeatures("muc_membersonly");
        } else {
            x.addFeatures("muc_open");
        }
        if (config.isRoomconfigModeratedRoom()) {
            x.addFeatures("muc_moderated");
        } else {
            x.addFeatures("muc_unmoderated");
        }
        if (config.affiliationCanViewJid(Affiliation.NONE)) {
            x.addFeatures("muc_nonanonymous");
        } else {
            x.addFeatures("muc_semianonymous");
        }

        serviceEntity.addItems(x);

    }

    private void configRoomDiscovery(String jid) {
        try {
            RoomConfiguration config = new RoomConfiguration(myDomain(), jid, this.mucRepository);
            configRoomDiscovery(config);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error on read room " + jid + " configuration", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getDefaults(Map<String, Object> params) {
        Map<String, Object> props = super.getDefaults(params);
        String[] hostnamesPropVal = null;
        if (params.get("--virt-hosts") != null) {
            hostnamesPropVal = ((String) params.get("--virt-hosts")).split(",");
        } else {
            hostnamesPropVal = DNSResolver.getDefHostNames();
        }
        for (int i = 0; i < hostnamesPropVal.length; i++) {
            if (((String) params.get("config-type")).equals(GEN_CONFIG_COMP)) {
                // This is specialized configuration for a single
                // external component and on specialized component like MUC
                hostnamesPropVal[i] = hostnamesPropVal[i];
            } else {
                hostnamesPropVal[i] = "muc." + hostnamesPropVal[i];
            }
        }

        // By default use the same repository as all other components:
        String repo_class = XML_REPO_CLASS_PROP_VAL;
        String repo_uri = XML_REPO_URL_PROP_VAL;
        String conf_db = null;
        if (params.get(GEN_USER_DB) != null) {
            conf_db = (String) params.get(GEN_USER_DB);
        } // end of if (params.get(GEN_USER_DB) != null)
        if (conf_db != null) {
            if (conf_db.equals("mysql")) {
                repo_class = MYSQL_REPO_CLASS_PROP_VAL;
                repo_uri = MYSQL_REPO_URL_PROP_VAL;
            }
            if (conf_db.equals("pgsql")) {
                repo_class = PGSQL_REPO_CLASS_PROP_VAL;
                repo_uri = PGSQL_REPO_URL_PROP_VAL;
            }
        } // end of if (conf_db != null)
        if (params.get(GEN_USER_DB_URI) != null) {
            repo_uri = (String) params.get(GEN_USER_DB_URI);
        } // end of if (params.get(GEN_USER_DB_URI) != null)
        props.put(MUC_REPO_CLASS_PROP_KEY, repo_class);
        props.put(MUC_REPO_URL_PROP_KEY, repo_uri);

        props.put(HOSTNAMES_PROP_KEY, hostnamesPropVal);
        return props;
    }

    public List<Element> getDiscoFeatures() {
        return null;
    }

    public Element getDiscoInfo(String node, String jid) {
        if (jid != null && JIDUtils.getNodeHost(jid).startsWith(getName() + ".")) {
            return serviceEntity.getDiscoInfo(node);
        }
        return null;
    }

    public List<Element> getDiscoItems(String node, String jid) {
        if (JIDUtils.getNodeHost(jid).startsWith(getName() + ".")) {
            return serviceEntity.getDiscoItems(node, null);
        } else {
            return Arrays.asList(serviceEntity.getDiscoItem(null, getName() + "." + jid));
        }
    }

    private String myDomain() {
        return getName() + "." + getDefHostName();
    }

    /*
     * (non-Javadoc)
     * 
     * @see tigase.muc.RoomListener#onConfigurationChange(tigase.muc.Room)
     */
    @Override
    public void onConfigurationChange(Room room) {
        ServiceEntity ent = this.serviceEntity.findNode(room.getRoomID());
        if (ent != null) {
            this.serviceEntity.removeItems(ent);
        }
        configRoomDiscovery(room.getConfiguration());
    };

    @Override
    public void onOccupantLeave(Room room) {
        int c = room.countOccupants();
        if (c == 0) {
            this.rooms.remove(room.getRoomID());
        }
    }

    private Element processDisco(IQ iq) {
        Element queryInfo = iq.getChild("query", "http://jabber.org/protocol/disco#info");
        Element queryItems = iq.getChild("query", "http://jabber.org/protocol/disco#items");

        String jid = iq.getTo().getBareJID().toString();

        if (queryInfo != null) {
            String node = queryInfo.getAttribute("node");
            Element result = getDiscoInfo(node, jid);
            return result;
        } else if (queryItems != null) {
            String node = queryItems.getAttribute("node");
            Element result = serviceEntity.getDiscoItem(node, jid);
            return result;
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void processPacket(Packet packet) {
        try {
            String roomName = JIDUtils.getNodeNick(packet.getElemTo());
            String roomHost = JIDUtils.getNodeHost(packet.getElemTo());

            String roomID = JIDUtils.getNodeID(packet.getElemTo());
            // String username = JIDUtils.getNodeResource(packet.getElemTo());

            if (roomName == null && "iq".equals(packet.getElemName())
                    && packet.getElement().getChild("unique", "http://jabber.org/protocol/muc#unique") != null) {
                Element iq = new Element("iq");
                iq.setAttribute("to", packet.getElemFrom());
                iq.setAttribute("from", packet.getElemTo());
                iq.setAttribute("id", packet.getElemId());
                iq.setAttribute("type", "result");

                String id;
                do {
                    id = generateUniqueName();
                } while (this.rooms.containsKey(id + "@" + roomHost));

                Element unique = new Element("unique", id, new String[] { "xmlns" },
                        new String[] { "http://jabber.org/protocol/muc#unique" });
                iq.addChild(unique);
                addOutPacket(new Packet(iq));
                return;
            } else if ("iq".equals(packet.getElemName())
                    && (packet.getElement().getChild("query", "http://jabber.org/protocol/disco#info") != null)
                    || packet.getElement().getChild("query", "http://jabber.org/protocol/disco#items") != null) {

                Packet result = packet.okResult(processDisco(new IQ(packet.getElement())), 0);
                addOutPacket(result);
                return;
            }

            Room room = this.rooms.get(roomID);
            List<Element> stanzasToSend = new LinkedList<Element>();
            if (room == null) {
                boolean newRoom = !this.allRooms.contains(roomID);
                room = new Room(myDomain(), this, mucRepository, roomID, JID.fromString(packet.getElemFrom()), newRoom);
                this.allRooms.add(roomID);
                this.rooms.put(roomID, room);
                if (newRoom) {
                    Presence presence = new Presence(packet.getElement());
                    stanzasToSend = room.processInitialStanza(presence);
                } else {
                    Presence presence = new Presence(packet.getElement());
                    stanzasToSend.addAll(room.processStanza(presence));
                }
            } else if ("message".equals(packet.getElemName())) {
                Message message = new Message(packet.getElement());
                stanzasToSend.addAll(room.processStanza(message));
            } else if ("presence".equals(packet.getElemName())) {
                Presence presence = new Presence(packet.getElement());
                stanzasToSend.addAll(room.processStanza(presence));
            } else if ("iq".equals(packet.getElemName())) {
                IQ iq = new IQ(packet.getElement());
                stanzasToSend.addAll(room.processStanza(iq));
            }

            if (stanzasToSend != null) {
                for (Element element : stanzasToSend) {
                    addOutPacket(new Packet(element));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.throwing("Muc Service", "processPacket", e);
        }
    }

    private void readAllRomms() {
        log.config("Reading rooms...");
        try {
            String[] roomsJid = this.mucRepository.getSubnodes(myDomain());
            allRooms.clear();
            if (roomsJid != null) {
                for (String jid : roomsJid) {
                    allRooms.add(jid);
                    configRoomDiscovery(jid);
                }
            }
        } catch (UserNotFoundException e) {
            try {
                this.mucRepository.addUser(myDomain());
            } catch (UserExistsException e1) {
                e1.printStackTrace();
            } catch (TigaseDBException e1) {
                e1.printStackTrace();
            }
        } catch (TigaseDBException e) {
            e.printStackTrace();
        }
        log.config(allRooms.size() + " known rooms.");
    }

    /** {@inheritDoc} */
    @Override
    public void setProperties(Map<String, Object> props) {
        super.setProperties(props);

        serviceEntity = new ServiceEntity(getName(), null, "Multi User Chat");
        serviceEntity.addIdentities(new ServiceIdentity("conference", "text", "Multi User Chat"));
        serviceEntity.addFeatures("http://jabber.org/protocol/muc", "muc_rooms");

        log.config("Register Service discovery " + serviceEntity.getJID());

        try {
            String cls_name = (String) props.get(MUC_REPO_CLASS_PROP_KEY);
            String res_uri = (String) props.get(MUC_REPO_URL_PROP_KEY);

            mucRepository = RepositoryFactory.getUserRepository("muc", cls_name, res_uri);
            mucRepository.initRepository(res_uri);

            log.config("Initialized " + cls_name + " as user repository: " + res_uri);
        } catch (Exception e) {
            log.severe("Can't initialize user repository: " + e);
            e.printStackTrace();
            System.exit(1);
        }
        String[] hostnames = (String[]) props.get(HOSTNAMES_PROP_KEY);
        clearRoutings();
        for (String host : hostnames) {
            addRouting(host);
        }
        readAllRomms();
        log.info("MUC Service started.");
        System.out.println(".");
    }

    @Override
    public void onDestroy(Room room) {
        ServiceEntity ent = this.serviceEntity.findNode(room.getRoomID());
        if (ent != null) {
            this.serviceEntity.removeItems(ent);
        }
        this.allRooms.remove(room.getRoomID());
        try {
            this.mucRepository.removeSubnode(myDomain(), room.getRoomID());
        } catch (UserNotFoundException e) {
            e.printStackTrace();
        } catch (TigaseDBException e) {
            e.printStackTrace();
        }
    }
}