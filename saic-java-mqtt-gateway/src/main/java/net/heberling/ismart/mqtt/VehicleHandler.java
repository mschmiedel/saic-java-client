package net.heberling.ismart.mqtt;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.heberling.ismart.Client;
import net.heberling.ismart.abrp.ABRP;
import net.heberling.ismart.asn1.v1_1.entity.VinInfo;
import net.heberling.ismart.asn1.v2_1.MessageCoder;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVCReq;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVCStatus25857;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVMVehicleStatusReq;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVMVehicleStatusResp25857;
import net.heberling.ismart.asn1.v2_1.entity.RvcReqParam;
import net.heberling.ismart.asn1.v3_0.entity.OTA_ChrgMangDataResp;
import org.bn.coders.IASN1PreparedElement;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VehicleHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(VehicleHandler.class);
  private final URI saicUri;
  private final String uid;
  private final String token;
  private final VinInfo vinInfo;
  private final SaicMqttGateway saicMqttGateway;
  private final IMqttClient client;
  private final String mqttVINPrefix;

  private final VehicleState vehicleState;

  public VehicleHandler(
      SaicMqttGateway saicMqttGateway,
      IMqttClient client,
      URI saicUri,
      String uid,
      String token,
      String mqttAccountPrefix,
      VinInfo vinInfo) {

    this.saicMqttGateway = saicMqttGateway;
    this.client = client;
    this.saicUri = saicUri;
    this.uid = uid;
    this.token = token;
    this.mqttVINPrefix = mqttAccountPrefix + "/vehicles/" + vinInfo.getVin();
    this.vinInfo = vinInfo;
    this.vehicleState = new VehicleState(client, mqttVINPrefix);
  }

  void handleVehicle() throws MqttException, IOException {
    vehicleState.configure(vinInfo);
    // we just got started, force some updates
    vehicleState.notifyCarActivityTime(ZonedDateTime.now(), true);
    while (true) {
      if (vehicleState.isRecentlyActive() && vehicleState.isAuxiliaryBatteryHealthy()) {
        OTA_RVMVehicleStatusResp25857 vehicleStatus =
            updateVehicleStatus(uid, token, vinInfo.getVin());
        OTA_ChrgMangDataResp chargeStatus = updateChargeStatus(uid, token, vinInfo.getVin());

        final String abrpApiKey = saicMqttGateway.getAbrpApiKey();
        final String abrpUserToken = saicMqttGateway.getAbrpUserToken(vinInfo.getVin());
        if (abrpApiKey != null
            && abrpUserToken != null
            && vehicleStatus != null
            && chargeStatus != null) {
          String abrpResponse =
              ABRP.updateAbrp(abrpApiKey, abrpUserToken, vehicleStatus, chargeStatus);
          MqttMessage msg = new MqttMessage(abrpResponse.getBytes(StandardCharsets.UTF_8));
          msg.setQos(0);
          msg.setRetained(true);
          client.publish(mqttVINPrefix + "/_internal/abrp", msg);
        }
      } else {
        try {
          // car not active, wait a second
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private OTA_RVMVehicleStatusResp25857 updateVehicleStatus(String uid, String token, String vin)
      throws IOException, MqttException {
    MessageCoder<OTA_RVMVehicleStatusReq> otaRvmVehicleStatusReqMessageCoder =
        new MessageCoder<>(OTA_RVMVehicleStatusReq.class);

    OTA_RVMVehicleStatusReq otaRvmVehicleStatusReq = new OTA_RVMVehicleStatusReq();
    otaRvmVehicleStatusReq.setVehStatusReqType(2);
    net.heberling.ismart.asn1.v2_1.Message<OTA_RVMVehicleStatusReq> vehicleStatusRequestMessage =
        otaRvmVehicleStatusReqMessageCoder.initializeMessage(
            uid, token, vin, "511", 25857, 1, otaRvmVehicleStatusReq);

    String vehicleStatusRequest =
        otaRvmVehicleStatusReqMessageCoder.encodeRequest(vehicleStatusRequestMessage);

    String vehicleStatusResponse =
        Client.sendRequest(saicUri.resolve("/TAP.Web/ota.mpv21"), vehicleStatusRequest);

    net.heberling.ismart.asn1.v2_1.Message<OTA_RVMVehicleStatusResp25857>
        vehicleStatusResponseMessage =
            new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVMVehicleStatusResp25857.class)
                .decodeResponse(vehicleStatusResponse);

    // we get an eventId back...
    vehicleStatusRequestMessage
        .getBody()
        .setEventID(vehicleStatusResponseMessage.getBody().getEventID());
    // ... use that to request the data again, until we have it
    // TODO: check for real errors (result!=0 and/or errorMessagePresent)
    while (vehicleStatusResponseMessage.getApplicationData() == null) {

      if (vehicleStatusResponseMessage.getBody().isErrorMessagePresent()) {
        if (vehicleStatusResponseMessage.getBody().getResult() == 2) {
          // TODO: relogn
        }
        // try again next time
        return null;
      }

      vehicleStatusRequestMessage.getBody().setUid(uid);
      vehicleStatusRequestMessage.getBody().setToken(token);

      SaicMqttGateway.fillReserved(vehicleStatusRequestMessage.getReserved());

      vehicleStatusRequest =
          otaRvmVehicleStatusReqMessageCoder.encodeRequest(vehicleStatusRequestMessage);

      vehicleStatusResponse =
          Client.sendRequest(saicUri.resolve("/TAP.Web/ota.mpv21"), vehicleStatusRequest);

      vehicleStatusResponseMessage =
          new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVMVehicleStatusResp25857.class)
              .decodeResponse(vehicleStatusResponse);

      LOGGER.debug(
          SaicMqttGateway.toJSON(
              SaicMqttGateway.anonymized(
                  new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                      OTA_RVMVehicleStatusResp25857.class),
                  vehicleStatusResponseMessage)));
    }

    vehicleState.handleVehicleStatusMessage(vehicleStatusResponseMessage);

    return vehicleStatusResponseMessage.getApplicationData();
  }

  private OTA_ChrgMangDataResp updateChargeStatus(String uid, String token, String vin)
      throws IOException, MqttException {
    net.heberling.ismart.asn1.v3_0.MessageCoder<IASN1PreparedElement>
        chargingStatusRequestMessageEncoder =
            new net.heberling.ismart.asn1.v3_0.MessageCoder<>(IASN1PreparedElement.class);

    net.heberling.ismart.asn1.v3_0.Message<IASN1PreparedElement> chargingStatusMessage =
        chargingStatusRequestMessageEncoder.initializeMessage(uid, token, vin, "516", 768, 5, null);

    String chargingStatusRequestMessage =
        chargingStatusRequestMessageEncoder.encodeRequest(chargingStatusMessage);

    LOGGER.debug(
        SaicMqttGateway.toJSON(
            SaicMqttGateway.anonymized(
                chargingStatusRequestMessageEncoder, chargingStatusMessage)));

    String chargingStatusResponse =
        Client.sendRequest(saicUri.resolve("/TAP.Web/ota.mpv30"), chargingStatusRequestMessage);

    net.heberling.ismart.asn1.v3_0.Message<OTA_ChrgMangDataResp> chargingStatusResponseMessage =
        new net.heberling.ismart.asn1.v3_0.MessageCoder<>(OTA_ChrgMangDataResp.class)
            .decodeResponse(chargingStatusResponse);

    LOGGER.debug(
        SaicMqttGateway.toJSON(
            SaicMqttGateway.anonymized(
                new net.heberling.ismart.asn1.v3_0.MessageCoder<>(OTA_ChrgMangDataResp.class),
                chargingStatusResponseMessage)));

    // we get an eventId back...
    chargingStatusMessage
        .getBody()
        .setEventID(chargingStatusResponseMessage.getBody().getEventID());
    // ... use that to request the data again, until we have it
    // TODO: check for real errors (result!=0 and/or errorMessagePresent)
    while (chargingStatusResponseMessage.getApplicationData() == null) {

      if (chargingStatusResponseMessage.getBody().isErrorMessagePresent()) {
        if (chargingStatusResponseMessage.getBody().getResult() == 2) {
          // TODO: relogn
        }
        // try again next time
        return null;
      }

      SaicMqttGateway.fillReserved(chargingStatusMessage.getReserved());

      LOGGER.debug(
          SaicMqttGateway.toJSON(
              SaicMqttGateway.anonymized(
                  chargingStatusRequestMessageEncoder, chargingStatusMessage)));

      chargingStatusRequestMessage =
          chargingStatusRequestMessageEncoder.encodeRequest(chargingStatusMessage);

      chargingStatusResponse =
          Client.sendRequest(saicUri.resolve("/TAP.Web/ota.mpv30"), chargingStatusRequestMessage);

      chargingStatusResponseMessage =
          new net.heberling.ismart.asn1.v3_0.MessageCoder<>(OTA_ChrgMangDataResp.class)
              .decodeResponse(chargingStatusResponse);

      LOGGER.debug(
          SaicMqttGateway.toJSON(
              SaicMqttGateway.anonymized(
                  new net.heberling.ismart.asn1.v3_0.MessageCoder<>(OTA_ChrgMangDataResp.class),
                  chargingStatusResponseMessage)));
    }
    vehicleState.handleChargeStatusMessage(chargingStatusResponseMessage);

    return chargingStatusResponseMessage.getApplicationData();
  }

  public void notifyMessage(SaicMessage message) throws MqttException {
    vehicleState.notifyMessage(message);
  }

  private void sendACCommand(byte command, byte temperature)
      throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException,
          MqttException, IOException {
    sendCommand(
        (byte) 6,
        new TreeMap<>(
            Map.of(19, new byte[] {command}, 20, new byte[] {temperature}, 255, new byte[] {0})));
  }

  private void sendCommand(byte type, SortedMap<Integer, byte[]> parameter)
      throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException,
          MqttException, IOException {
    MessageCoder<OTA_RVCReq> otaRvcReqMessageCoder = new MessageCoder<>(OTA_RVCReq.class);

    // we send a command end expect the car to wake up
    vehicleState.notifyCarActivityTime(ZonedDateTime.now(), false);

    OTA_RVCReq req = new OTA_RVCReq();
    req.setRvcReqType(new byte[] {type});
    if (parameter != null && !parameter.isEmpty()) {
      List<RvcReqParam> params = new ArrayList<>();
      req.setRvcParams(params);

      parameter.forEach(
          (key, value) -> {
            RvcReqParam param = new RvcReqParam();
            param.setParamId(key);
            param.setParamValue(value);
            params.add(param);
          });
    }

    net.heberling.ismart.asn1.v2_1.Message<OTA_RVCReq> sendCommandRequest =
        otaRvcReqMessageCoder.initializeMessage(uid, token, vinInfo.getVin(), "510", 25857, 1, req);

    String sendCommandRequestMessage = otaRvcReqMessageCoder.encodeRequest(sendCommandRequest);

    String sendCommandResponseMessage =
        Client.sendRequest(saicUri.resolve("/TAP.Web/ota.mpv21"), sendCommandRequestMessage);

    final MessageCoder<OTA_RVCStatus25857> otaRvcStatus25857MessageCoder =
        new MessageCoder<>(OTA_RVCStatus25857.class);
    net.heberling.ismart.asn1.v2_1.Message<OTA_RVCStatus25857> sendCommandReqestMessage =
        otaRvcStatus25857MessageCoder.decodeResponse(sendCommandResponseMessage);

    // ... use that to request the data again, until we have it
    // TODO: check for real errors (result!=0 and/or errorMessagePresent)
    while (sendCommandReqestMessage.getApplicationData() == null) {
      if (sendCommandReqestMessage.getBody().isErrorMessagePresent()) {
        if (sendCommandReqestMessage.getBody().getResult() == 2) {
          // TODO:
          // getBridgeHandler().relogin();
        }
        throw new TimeoutException(
            new String(sendCommandReqestMessage.getBody().getErrorMessage()));
      }
      SaicMqttGateway.fillReserved(sendCommandRequest.getReserved());

      if (sendCommandReqestMessage.getBody().getResult() == 0) {
        // we get an eventId back...
        sendCommandRequest.getBody().setEventID(sendCommandReqestMessage.getBody().getEventID());
      } else {
        // try a fresh eventId
        sendCommandRequest.getBody().setEventID(0);
      }

      sendCommandRequestMessage = otaRvcReqMessageCoder.encodeRequest(sendCommandRequest);

      sendCommandResponseMessage =
          Client.sendRequest(saicUri.resolve("/TAP.Web/ota.mpv21"), sendCommandRequestMessage);

      sendCommandReqestMessage =
          otaRvcStatus25857MessageCoder.decodeResponse(sendCommandResponseMessage);
    }

    LOGGER.debug(
        "Got SendCommand Response message: {}",
        SaicMqttGateway.toJSON(
            SaicMqttGateway.anonymized(otaRvcStatus25857MessageCoder, sendCommandReqestMessage)));
  }

  public void handleMQTTCommand(String topic, MqttMessage message) throws MqttException {
    try {
      if (message.isRetained()) {
        throw new IOException("Message may not be retained");
      }
      switch (topic) {
        case "drivetrain/hvBatteryActive":
          switch (message.toString().toLowerCase()) {
            case "true":
              vehicleState.setHVBatteryActive(true);
              break;
            case "false":
              vehicleState.setHVBatteryActive(false);
              break;
            default:
              throw new IOException("Unsupported payload " + message);
          }
          break;
        case "climate/remoteClimateState":
          switch (message.toString().toLowerCase()) {
            case "off":
              sendACCommand((byte) 0, (byte) 0);
              break;
            case "on":
              sendACCommand((byte) 2, (byte) 8);
              break;
            case "front":
              sendACCommand((byte) 5, (byte) 8);
              break;
            default:
              throw new IOException("Unsupported payload " + message);
          }
          break;
        case "doors/locked":
          switch (message.toString().toLowerCase()) {
            case "true":
              sendCommand((byte) 0x01, new TreeMap<>(Map.of()));
              break;
            case "false":
              sendCommand(
                  (byte) 0x02,
                  new TreeMap<>(
                      Map.of(
                          4,
                          new byte[] {(byte) 0x00},
                          5,
                          new byte[] {(byte) 0x00},
                          6,
                          new byte[] {(byte) 0x00},
                          7,
                          new byte[] {(byte) 0x03},
                          255,
                          new byte[] {(byte) 0x00})));
              break;
            default:
              throw new IOException("Unsupported payload " + message);
          }
          break;
        default:
          throw new IOException("Unsupported topic " + topic);
      }
      MqttMessage msg = new MqttMessage("Success".getBytes(StandardCharsets.UTF_8));
      msg.setQos(0);
      msg.setRetained(false);
      client.publish(mqttVINPrefix + "/" + topic + "/result", msg);

    } catch (URISyntaxException
        | ExecutionException
        | InterruptedException
        | TimeoutException
        | IOException e) {
      MqttMessage msg =
          new MqttMessage(("Command failed. " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
      msg.setQos(0);
      msg.setRetained(false);
      client.publish(mqttVINPrefix + "/" + topic + "/result", msg);
    }
  }
}
