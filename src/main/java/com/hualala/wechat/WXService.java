package com.hualala.wechat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hualala.pay.domain.WxPayRes;
import com.hualala.user.domain.User;
import com.hualala.util.BeanParse;
import com.hualala.util.CacheUtils;
import com.hualala.util.HttpClientUtil;
import com.hualala.wechat.common.RedisKey;
import com.hualala.wechat.common.TemplateCode;
import com.hualala.wechat.common.WXConstant;
import com.hualala.wechat.component.WXConfig;
import com.hualala.wechat.domain.CustomMsg;
import com.hualala.wechat.domain.TemplateMsg;
import com.hualala.weixin.mp.JSApiUtil;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hualala.wechat.common.WXConstant.*;

/**
 * @author YuanChong
 * @create 2019-06-26 22:51
 * @desc
 */
@Log4j2
@Service
public class WXService {

    @Autowired
    private WXConfig wxConfig;

    /**
     * 发模板消息线程
     */
    private ExecutorService templatePool = Executors.newSingleThreadExecutor();

    private final Integer monthSeconds = 30 * 24 * 60 * 60;

    /**
     * 获取公众号的AccessToken
     *
     * @return
     */
    public String getAccessToken() {
        String redisKey = String.format(RedisKey.ACCESS_TOKEN_KEY, wxConfig.getAppID());
        String accessToken = CacheUtils.get(redisKey);
        return accessToken;
    }

    /**
     * 获取公众号的JSTicket
     *
     * @return
     */
    public String getJSTicket() {
        String ticketKey = String.format(RedisKey.JSAPI_TICKET_KEY, wxConfig.getAppID());
        String ticket = CacheUtils.get(ticketKey);
        return ticket;
    }

    public String getAppID() {
        return wxConfig.getAppID();
    }

    /**
     * 刷新微信公众号的access_token
     * https请求:
     * https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=APPID&secret=APPSECRET
     * 微信返回数据:
     * {"access_token":"ACCESS_TOKEN","expires_in":7200}
     *
     * @return
     */
    public String refreshToken() {
        String redisKey = String.format(RedisKey.ACCESS_TOKEN_KEY, wxConfig.getAppID());
        String url = String.format(ACCESS_TOKEN_URL, wxConfig.getAppID(), wxConfig.getSecret());
        HttpClientUtil.HttpResult result = HttpClientUtil.post(url);
        log.info("获取微信公众号的access_token: {}", result.getContent());
        String accessToken = JSON.parseObject(result.getContent()).getString("access_token");
        int expire = wxConfig.getExpire() + 200;
        CacheUtils.set(redisKey, accessToken, expire);
        return accessToken;
    }


    /**
     * 刷新jsapi_ticket
     * https请求:
     * https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=ACCESS_TOKEN&type=jsapi
     * 微信返回数据:
     * {"errcode":0,"errmsg":"ok","ticket":"JSAPI_TICKET","expires_in":7200}
     *
     * @return
     */
    public String refreshJSTicket() {
        String accessToken = getAccessToken();
        //获取JS-api ticket
        String ticketUrl = String.format(JSAPI_TICKET_URL, accessToken);
        HttpClientUtil.HttpResult ticketResult = HttpClientUtil.post(ticketUrl);
        log.info("获取微信公众号的jsApi ticket : {}", ticketResult.getContent());
        String ticket = JSON.parseObject(ticketResult.getContent()).getString("ticket");
        String ticketKey = String.format(RedisKey.JSAPI_TICKET_KEY, wxConfig.getAppID());
        int expire = wxConfig.getExpire() + 200;
        CacheUtils.set(ticketKey, ticket, expire);
        return ticket;
    }


    /**
     * 通过 openID查询用户基本信息
     * https请求:
     * https://api.weixin.qq.com/cgi-bin/user/info?access_token=ACCESS_TOKEN&openid=OPENID&lang=zh_CN
     *
     * @param openID
     * @return
     */
    public User userBaseInfo(String openID) {
        String accessToken = getAccessToken();
        String url = String.format(USER_BASE_INFO_URL, accessToken, openID);
        HttpClientUtil.HttpResult result = HttpClientUtil.post(url);
        log.info("通过OpenID来获取用户基本信息: {}", result.getContent());
        User user = JSONObject.parseObject(result.getContent(), User.class);
        if (StringUtils.isEmpty(user.getOpenid())) {
            log.error("通过OpenID来获取用户基本信息微信返回错误 url {} result {}", url, result);
            throw new RuntimeException("openID查询用户基本信息失败");
        }
        return user;
    }

    /**
     * 通过code授权码换取网页授权access_token
     * https请求:
     * https://api.weixin.qq.com/sns/oauth2/access_token?appid=APPID&secret=SECRET&code=CODE&grant_type=authorization_code
     * 微信返回数据:
     * {"access_token":"ACCESS_TOKEN","expires_in":7200,"refresh_token":"REFRESH_TOKEN","openid":"OPENID","scope":"SCOPE"}
     *
     * @param code
     * @return
     */
    public JSONObject webAccessToken(String code) {
        String url = String.format(JS_ACCESS_TOKEN_URL, wxConfig.getAppID(), wxConfig.getSecret(), code);
        HttpClientUtil.HttpResult tokenResult = HttpClientUtil.post(url);
        log.info("通过code授权码换取网页授权access_token: {}", tokenResult.getContent());
        JSONObject result = JSONObject.parseObject(tokenResult.getContent());
        if (StringUtils.isEmpty(result.getString("access_token"))) {
            log.error("通过code授权码换取网页授权access_token微信返回错误 url {} result {}", url, result);
            throw new RuntimeException("通过code授权码换取网页授权access_token失败");
        }
        String openid = result.getString("openid");
        Integer expiresIn = result.getInteger("expires_in");
        String tokenKey = String.format(RedisKey.WEB_ACCESS_TOKEN_KEY, wxConfig.getAppID(), openid);
        //先保存起来这个web token 暂时没什么用
        CacheUtils.set(tokenKey, result.toJSONString(), expiresIn);
        return result;
    }


    /**
     * 网页授权拉取用户信息(需scope为 snsapi_userinfo)
     * https请求:
     * https://api.weixin.qq.com/sns/userinfo?access_token=ACCESS_TOKEN&openid=OPENID&lang=zh_CN
     * 微信返回数据:
     * {"openid":" OPENID","nickname": NICKNAME,"sex":"1","province":"PROVINCE""city":"CITY","country":"COUNTRY","headimgurl":"","privilege":["PRIVILEGE1" "PRIVILEGE2"],"unionid":"UNIONID"}
     *
     * @param accessToken
     * @param openid
     * @return
     */
    public User webUserInfo(String accessToken, String openid) {
        String url = String.format(JS_USER_BASE_INFO_URL, accessToken, openid);
        HttpClientUtil.HttpResult result = HttpClientUtil.post(url);
        log.info("网页授权拉取用户信息: {}", result.getContent());
        User user = JSONObject.parseObject(result.getContent(), User.class);
        if (StringUtils.isEmpty(user.getOpenid())) {
            log.error("网页授权拉取用户信息微信返回错误 url {} result {}", url, result);
            throw new RuntimeException("网页授权拉取用户信息失败");
        }
        return user;
    }

    /**
     * 获取微信临时二维码
     * <p>
     * 创建二维码ticket
     * http请求方式: POST
     * URL: https://api.weixin.qq.com/cgi-bin/qrcode/create?access_token=TOKEN
     * POST数据例子：{"expire_seconds": 604800, "action_name": "QR_STR_SCENE", "action_info": {"scene": {"scene_str": "test"}}}
     * 微信返回数据: {"ticket":"gQH47joAAAAAAAAA....","expire_seconds":60,"url":"http://weixin.qq.com/q/kZgfwMTm72WWPkovabbI"}
     * <p>
     * 通过ticket换取二维码
     * HTTP 请求（请使用https协议）https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=TICKET
     * 提醒：TICKET记得进行UrlEncode
     *
     * @param sceneStr
     * @param seconds
     * @return
     */
    public String qrcodeTicket(String sceneStr, Integer seconds) {
        //二维码的参数
        String accessToken = getAccessToken();
        String url = String.format(QRCODE_TICKET_URL, accessToken);
        String ticketReq = "{\"expire_seconds\": %s, \"action_name\": \"QR_STR_SCENE\", \"action_info\": {\"scene\": {\"scene_str\": \"%s\"}}}";
        ticketReq = String.format(ticketReq, seconds, sceneStr);
        HttpClientUtil.HttpResult ticketRes = HttpClientUtil.postJson(url, ticketReq);
        String ticket = JSON.parseObject(ticketRes.getContent()).getString("ticket");
        if (StringUtils.isEmpty(ticket)) {
            log.error("获取微信临时二维码 url {} result {}", url, ticketRes);
            throw new RuntimeException("获取微信临时二维码失败");
        }
        return ticket;
    }


    /**
     * 生成微信JS-SDK签名
     *
     * @param data
     * @return
     * @throws UnsupportedEncodingException
     * @throws NoSuchAlgorithmException
     */
    public Map<String, Object> jsApiSignature(Map<String, Object> data, String url) throws UnsupportedEncodingException, NoSuchAlgorithmException {
        String noncestr = UUID.randomUUID().toString();
        String jsapiTicket = getJSTicket();
        String timestamp = Long.toString(System.currentTimeMillis());
        String params = String.format(JSAPI_SIGNATURE, jsapiTicket, noncestr, timestamp, url);
        //生成签名
        String signature = JSApiUtil.generateSignature(params);
        data.put("noncestr", noncestr);
        data.put("timestamp", timestamp);
        data.put("signature", signature);
        data.put("appID", wxConfig.getAppID());
        return data;
    }

    /**
     * 下载多媒体文件
     *
     * @param mediaID
     * @return
     * @throws Exception
     */
    public byte[] downloadMedia(String mediaID) {
        String accessToken = getAccessToken();
        String url = String.format(DOWNLOAD_MEDIA, accessToken, mediaID);
        return HttpClientUtil.downLoadFromUrl(url);
    }

    /**
     * 微信支付创建订单
     *
     * @param xml
     * @return
     */
    public WxPayRes payOrder(String xml) throws IOException {
        HttpClientUtil.HttpResult result = HttpClientUtil.postXML(WX_ORDER_PAY, xml);
        WxPayRes wxPayRes = BeanParse.XMLToBean(result.getContent(), WxPayRes.class);
        log.info("微信支付创建订单 wxPayRes: {}", wxPayRes);
        if (!wxPayRes.getReturnCode().equals(WXConstant.SUCCESS)) {
            throw new RuntimeException("【微信统一支付】发起支付, returnCode != SUCCESS, returnMsg = " + wxPayRes.getReturnMsg());
        }
        if (!wxPayRes.getResultCode().equals(WXConstant.SUCCESS)) {
            throw new RuntimeException("【微信统一支付】发起支付, resultCode != SUCCESS, err_code = " + wxPayRes.getErrCode() + " err_code_des=" + wxPayRes.getErrCodeDes());
        }
        return wxPayRes;
    }

    /**
     * 获取预授权重定向的url
     *
     * @param requestURL
     * @return
     * @throws UnsupportedEncodingException
     */
    public String preAuthUrl(String requestURL) throws UnsupportedEncodingException {
        String encoderUrl = URLEncoder.encode(requestURL, StandardCharsets.UTF_8.name());
        return String.format(JS_PRE_AUTH_URL, wxConfig.getAppID(), encoderUrl, "snsapi_userinfo", "NOTHING");
    }


    /**
     * 异步发模板消息
     *
     * @param msg
     */
    public void asynSendMsg(TemplateMsg msg) {
        templatePool.execute(() -> this.sendMsg(msg));
    }

    /**
     * 发送模板消息
     *
     * @param message
     * @return
     */
    public HttpClientUtil.HttpResult sendMsg(TemplateMsg message) {
        //获取模板ID
        String templateID = syncTemplateID(message.getTemplateCode());
        message.setTemplateID(templateID);
        //推送消息
        String accessToken = getAccessToken();
        String url = String.format(SEND_TEMPLATE_URL, accessToken);
        String body = JSON.toJSONString(message);
        log.info("微信公众号推送 url: {}, body: {}", url, body);
        HttpClientUtil.HttpResult ticketRes = HttpClientUtil.postJson(url, body);
        return ticketRes;
    }



    public void asynSendQrcode(String openID) {
        templatePool.execute(() -> this.sendQrcode(openID));
    }


    public void sendQrcode(String openID) {
        //生成二维码ticket 30天过期
        String qrcodeTicket = this.qrcodeTicket(openID, monthSeconds);
        //获取二维码数据
        String showQrcodeUrl = String.format(SHOW_QRCODE_URL, qrcodeTicket);
        byte[] media = HttpClientUtil.downLoadFromUrl(showQrcodeUrl);
        //上传临时二维码文件 获取mediaID
        String mediaID = this.uploadImage("image", media);
        //构建二维码客服消息
        CustomMsg customMsg = new CustomMsg(openID, "image");
        customMsg.buildImage(mediaID);
        this.sendMsg(customMsg);
    }


    /**
     * 发送客服消息
     * @param customMsg
     */
    public void sendMsg(CustomMsg customMsg) {
        String params = customMsg.build();
        String accessToken = getAccessToken();
        String customUrl = String.format(CUSTOM_SEND_URL, accessToken);
        HttpClientUtil.postJson(customUrl, params);
    }


    /**
     * 通过模板编码获取模板ID
     *
     * @param templateCode
     * @return
     */
    public String syncTemplateID(String templateCode) {
        String redisKey = String.format(RedisKey.TEMPLATEID_KEY, wxConfig.getAppID(), templateCode);
        String templateID = CacheUtils.get(redisKey);
        if (StringUtils.isEmpty(templateID)) {
            String accessToken = getAccessToken();
            String idUrl = String.format(ADD_TEMPLATEID_URL, accessToken);
            JSONObject idReq = new JSONObject();
            idReq.put("template_id_short", templateCode);
            HttpClientUtil.HttpResult idRes = HttpClientUtil.postJson(idUrl, idReq.toJSONString());
            log.info("模板ID从缓存获取失败，通过模板编码获取模板ID url: {}, body: {} result:{}", idUrl, idReq, idRes);
            templateID = JSON.parseObject(idRes.getContent()).getString("template_id");
            CacheUtils.set(redisKey, templateID);
        }
        return templateID;
    }

    /**
     * 上传临时文件到微信
     *
     * @param type
     * @param media
     */
    public String uploadImage(String type, byte[] media) {
        String accessToken = getAccessToken();
        String uploadUrl = String.format(UPLOAD_MEDIA_URL, accessToken, type);
        HttpClientUtil.HttpResult httpResult = HttpClientUtil.postFile(uploadUrl, "media", media);
        JSONObject jsonResult = JSON.parseObject(httpResult.getContent());
        if (StringUtils.isEmpty(jsonResult.getString("media_id"))) {
            log.error("上传临时文件到微信 url {} result {}", uploadUrl, httpResult.getContent());
            throw new RuntimeException("上传临时文件到微信失败");
        }
        return jsonResult.getString("media_id");
    }


    public String getVipSuccessTemplateCode() {
        return TemplateCode.BECOME_VIP_SUCCESS;
    }


}
