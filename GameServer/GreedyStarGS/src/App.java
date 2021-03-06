import io.grpc.stub.StreamObserver;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stream.Simple;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class App extends GameServerRoomEventHandler {

    private static Logger log = LoggerFactory.getLogger("App");
    //    private Map<Long,ArrayList<Food>> fondMap = new HashMap<>();
    private static AtomicLong clock = new AtomicLong();
    public Map<Long, GreedyStarRoom> greedRoomMap = new HashMap(256);

    public static void main(String[] args) {
        String[] path = new String[1];


        /**
         * 本地调试时在此处填写自己config.Json的绝对路径,正式发布上线注释下面代码即可。
         */
        path[0] = "E:\\project\\GreedyStar\\GameServer\\Config.json";
        try {
            int i1 = 10000 * 10000;
            long lastTime = System.currentTimeMillis();
            for (int i = 0; i < i1; i++) {
                clock.getAndIncrement();
            }
            log.info("BenchMark: {}/624", (System.currentTimeMillis() - lastTime));
            Main.main(path);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onReceive(Simple.Package.Frame clientEvent, StreamObserver<Simple.Package.Frame> clientChannel) {
        try {
            super.onReceive(clientEvent, clientChannel);
            Gsmvs.Request request = null;
            request = Gsmvs.Request.parseFrom(clientEvent.getMessage());

            switch (clientEvent.getCmdId()) {
                // 接收客户端发来的消息
                case Gshotel.HotelGsCmdID.HotelBroadcastCMDID_VALUE:
                    Gshotel.HotelBroadcast boardMsg = Gshotel.HotelBroadcast.parseFrom(clientEvent.getMessage());
                    String msg = boardMsg.getCpProto().toStringUtf8();
                    try {
                        examplePush(boardMsg.getRoomID(), boardMsg.getUserID(), msg, request, clientChannel);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    break;
                case Gsmvs.MvsGsCmdID.MvsKickPlayerReq_VALUE:
                    leaveRoom(request);
                    break;
                case Gsmvs.MvsGsCmdID.MvsLeaveRoomReq_VALUE:
                    leaveRoom(request);
                    break;
                case Gshotel.HotelGsCmdID.HotelPlayerCheckin_VALUE:
                    JoinRoom(request, clientChannel);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("game logic error:", e);
        }
        return false;
    }


    /**
     * 玩家加入房间
     *
     * @param request       返回的数据
     * @param clientChannel 房间信息通道
     */
    private void JoinRoom(Gsmvs.Request request, StreamObserver<Simple.Package.Frame> clientChannel) {
        long roomID = request.getRoomID();
        int requestUserID = request.getUserID();

        if (!greedRoomMap.containsKey(roomID)) {
            GreedyStarRoom room = new GreedyStarRoom(roomID, clientChannel, this);
            greedRoomMap.put(roomID, room);
            for (int i = 0; i < Const.FOOD_INITIAL_NUB; i++) {
                Food food = Food.addFood(i);
                room.foodList.add(food);
            }
            room.foodNum = room.foodList.size();
        }

    }

    /**
     * 同步房间内的游戏状态(user&food)给某个玩家.
     * @param roomID 房间ID
     * @param requestUserID 信息的接收者ID
     */
    private void syncRoomState2User(long roomID, int requestUserID) {
        GreedyStarRoom room = greedRoomMap.get(roomID);
        if(room==null){
            log.warn("room has not exist {}",roomID);
            return;
        }
        syncMyself(room, requestUserID);
        sendFoodMsg(room.foodList, roomID, requestUserID);
        //同步其他的玩家的信息
        ArrayList<GreedStarUser> arrayList = new ArrayList<>();
        for (int i = 0; i < room.userList.size(); i++) {
            if (requestUserID != room.userList.get(i).userID) {
                arrayList.add(room.userList.get(i));
            }
        }
        GameServerMsg msg;
        if (arrayList.size() > 0) {
            msg = new GameServerMsg("otherPlayer", arrayList);
            log.info("otherPlayer" + JsonUtil.toString(msg));
            sendMsgInclude(roomID, JsonUtil.toString(msg).getBytes(), new int[]{requestUserID});
        }
    }

    /**
     * game server exit
     * 玩家离开房间
     *
     * @param request
     */
    private void leaveRoom(Gsmvs.Request request) {
        long roomID = request.getRoomID();
        if (greedRoomMap.containsKey(roomID)) {
            if (!roomRemoveUser1(greedRoomMap.get(roomID), request.getUserID())) {
                log.warn("not found userID:" + request.getUserID());
            } else {
                if (greedRoomMap.get(request.getRoomID()).userList.size() <= 0) {
                    greedRoomMap.get(request.getRoomID()).destroy();
                    greedRoomMap.remove(request.getRoomID());
                }
            }
        } else {
            log.warn("not found roomID:" + request.getRoomID());
        }
    }

    /**
     * 发送初始创建食物的信息
     *
     * @param foodArrayList 食物列表
     * @param roomID        房间ID
     * @param userID        用户ID 用户ID为0，就给房间中全部用户发送消息
     */
    private void sendFoodMsg(ArrayList<Food> foodArrayList, long roomID, int userID) {
        List<Food> foods_one = foodArrayList.subList(0, 19);
        List<Food> foods_two = foodArrayList.subList(20, 39);
        List<Food> foods_three = foodArrayList.subList(40, 59);
        List[] foods = new List[]{foods_one, foods_two, foods_three};
        for (List food : foods) {
            GameServerMsg msg = new GameServerMsg("addFood", food);
            if (userID == 0) {
                broadcast(roomID, JsonUtil.toString(msg).getBytes());
            } else {
                sendMsgInclude(roomID, JsonUtil.toString(msg).getBytes(), new int[]{userID});
            }
        }
    }


    /**
     * 同步某个玩家独有的信息
     *
     * @param room   玩家
     * @param userID 用户ID
     */
    private void syncMyself(GreedyStarRoom room, int userID) {
        int[] Position = Utils.getRandomPosition();
        GreedStarUser user = null;
        for (int i = 0; i < room.userList.size(); i++) {
            GreedStarUser temp = room.userList.get(i);
            if (temp.userID == userID) {
                user = temp;
                log.warn("user {} has exist .", temp.userID);
                break;
            }
        }
        if (user == null) {
            user = new GreedStarUser(userID, Const.USER_IN_THE_GAME, 0,
                    Const.USER_SIZE, Position[0], Position[1], Const.SPEED,
                    new Input());
            room.userList.add(user);
        }
        
        GameServerMsg msg = new GameServerMsg("addPlayer", user);
        String s = JsonUtil.toString(msg);
        broadcast(room.ID, s.getBytes());
        log.warn("[SYNC] userSelf {}  .", userID);
        msg.type = "countDown";
        msg.data = room.countDown;
        broadcast(room.ID, s.getBytes());


    }

    /**
     * @param room   房间信息
     * @param userID 用户ID
     */
    private boolean roomRemoveUser1(GreedyStarRoom room, int userID) {
        for (int i = 0; i < room.userList.size(); i++) {
            if (userID == room.userList.get(i).userID) {
                GameServerMsg msg = new GameServerMsg("removePlayer", room.userList.get(i));
                room.userList.remove(i);
                broadcast(room.ID, JsonUtil.toString(msg).getBytes());
                return true;
            }
        }
        return false;
    }


    @Override
    public void onStart() {
        log.info("onStart");

    }

    @Override
    public void onStop() {
        log.info("onStop");
    }


    /**
     * API使用示例
     *
     * @param msg
     */
    private void examplePush(long roomID, int userID, String msg, Gsmvs.Request request, StreamObserver<Simple.Package.Frame> clientChannel) throws JSONException {
        if (msg == null) {
            return;
        }
        JSONObject jsonObject = new JSONObject(msg);
        String type = jsonObject.optString("type");
        if (type == null) {
            log.info("user {} jsonObject no 'type' {}", userID, msg);
            return;
        }
        GreedyStarRoom room = greedRoomMap.get(roomID);
        if (!"ready".equals(type) && (room == null || room.channel == null)) {
            log.info(" not in room or channel is null ,roomID:{} user: {} ,msg: {}", roomID, userID, msg);
            return;
        }

        switch (type) {
            case "input":
                //gs 停止时，玩家创建房间，gs 未存储房间，此处需要判断空,调试时常遇到
                if (room != null && room.userList != null) {
                    for (int i = 0; i < room.userList.size(); i++) {
                        if (userID == room.userList.get(i).userID) {
                            room.userList.get(i).input = JsonUtil.fromJson(jsonObject.getString("data"), Input.class);
                            break;
                        }
                    }
                }
                break;
            case "startGame":
                if (room != null && room.userList != null) {
                    syncRoomState2User(roomID, userID);
                }
                break;
            case "ping":
                sendMsgInclude(roomID, msg.getBytes(), new int[]{userID});
                break;
        }

    }

    public boolean sendMsg(long roomID, String msgType, Object msgData) {
        GameServerMsg gameServerMsg = new GameServerMsg(msgType, msgData);
        boolean sendResult = sendMsgToAllUserInRoom(roomID, JsonUtil.toString(gameServerMsg).getBytes());
        return sendResult;
    }

}
