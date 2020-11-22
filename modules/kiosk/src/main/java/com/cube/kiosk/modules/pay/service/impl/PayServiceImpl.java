package com.cube.kiosk.modules.pay.service.impl;


import com.cube.common.https.SSLClient;
import com.cube.common.utils.IpUtil;
import com.cube.common.utils.SnowflakeIdWorker;
import com.cube.kiosk.modules.common.ResponseData;
import com.cube.kiosk.modules.common.ResponseHisData;
import com.cube.kiosk.modules.common.model.ResultListener;
import com.cube.kiosk.modules.common.utils.HisMd5Sign;
import com.cube.kiosk.modules.common.utils.HttpsRestTemplate;
import com.cube.kiosk.modules.common.utils.RestTemplate;
import com.cube.kiosk.modules.hardware.repository.HardWareRecordRepository;
import com.cube.kiosk.modules.patient.model.Patient;
import com.cube.kiosk.modules.patient.repository.PatientRepository;
import com.cube.kiosk.modules.pay.model.PayParam;
import com.cube.kiosk.modules.pay.model.QueryResult;
import com.cube.kiosk.modules.pay.model.TransQueryParam;
import com.cube.kiosk.modules.pay.model.TransactionData;
import com.cube.kiosk.modules.pay.repository.QueryResultRepository;
import com.cube.kiosk.modules.pay.repository.TransactionRepository;
import com.cube.kiosk.modules.pay.service.PayService;
import com.cube.kiosk.modules.pay.utils.IdWorker;

import com.cube.kiosk.modules.security.model.HardWareConfigDO;
import com.cube.kiosk.modules.security.repository.HardWareRepository;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import javax.transaction.Transactional;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author LMZ
 */
@Service
public class PayServiceImpl implements PayService {

    @Autowired
    private HardWareRepository hardWareRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Value("${app-pay.mid}")
    private String mid;

    @Value("${app-pay.noticeUrl}")
    private String callBack;

    @Autowired
    private HttpsRestTemplate httpsRestTemplate;

    @Autowired
    private QueryResultRepository queryResultRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private PatientRepository patientRepository;


    @Value("${neofaith.token}")
    private String token;

    @Value("${neofaith.hosId}")
    private String hosId;

    public void doPost(PayParam payParam, ResultListener linstener) {
        String charset = "utf-8";
        SSLClient sslClient = null;
        try {
            sslClient = new SSLClient();
            Map<String,Object> requestJson = new HashMap<>(16);
            Gson gson = new Gson();
            requestJson.put("posNo","");
            requestJson.put("tranType","F");
            requestJson.put("merTradeNo", IdWorker.getInstance().nextId()+"");
            requestJson.put("mid",mid);

            requestJson.put("txnAmt", payParam.getTxnAmt());
            String a = requestJson.toString();
            String b = a.substring(1,a.length()-1);
//            String result = sslClient.doPost("https://"+ip+":"+port+"/comlink-interface-abc-forward/comlink/pay", b, charset);
//            ResponseDataPay responseDataPay = gson.fromJson(result, new TypeToken<ResponseDataPay>(){}.getType());
//            if("00".equals(responseDataPay.getRespCode())){
//                linstener.success(payParam);
//            }else {
//                linstener.error(payParam);
//            }

        } catch (Exception e) {
            linstener.exception(e.getMessage());
        }


    }

    @Override
    public void getQrCode(PayParam payParam, ResultListener linstener) {
        try{
            HardWareConfigDO hardWareConfigDO = hardWareRepository.findByIp(IpUtil.getRemoteAddr());
            if(hardWareConfigDO !=null){
                payParam.setPosNo(hardWareConfigDO.getPosNo());
                payParam.setTid(hardWareConfigDO.getTid());
                payParam.setTxnAmt(payParam.getMoney());
                NumberFormat nf = NumberFormat.getInstance();
                //设置是否使用分组
                nf.setGroupingUsed(false);
                //设置最大整数位数
                nf.setMaximumIntegerDigits(6);
                //设置最小整数位数
                nf.setMinimumIntegerDigits(6);
                payParam.setTraceNo(nf.format(hardWareConfigDO.getTraceNo()));

                hardWareConfigDO.setTraceNo(hardWareConfigDO.getTraceNo()+1);
                hardWareRepository.save(hardWareConfigDO);
            }
            TransactionData transactionData = new TransactionData();

            transactionData.setPosNo(payParam.getPosNo());
            transactionData.setTranType("F");
            transactionData.setTxnAmt(payParam.getTxnAmt());
            transactionData.setTraceNo(payParam.getTraceNo());
            SnowflakeIdWorker idWorker = new SnowflakeIdWorker(0, 0);
            long id = idWorker.nextId();
            transactionData.setMerTradeNo(id+"");
            transactionData.setMid(mid);
            transactionData.setTid(payParam.getTid());
            Gson gson = new Gson();
            String transParam = gson.toJson(transactionData);


            String result = restTemplate.doPostBankApi(transParam,"");
            transactionData = gson.fromJson(result,TransactionData.class);
            transactionData.setCardNo(payParam.getCardNo());
            //交易成功回调
            transactionData.setCallBackUrl(callBack);
            transactionRepository.save(transactionData);
            if("00".equals(transactionData.getRespCode())){
                linstener.success(transactionData);
            }else {
                linstener.error(transactionData);
            }
        }
        catch (JsonSyntaxException jsonSyntaxException){
            linstener.exception(String.format("反序列化交易结果失败:%s",jsonSyntaxException.getMessage()));
        }
        catch (Exception exception){
            linstener.exception(exception.getMessage());
        }

    }

    @Override
    public void queryResult(String qrCodeUrl, ResultListener linstener) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            Gson gson = new Gson();
            TransactionData transactionData = transactionRepository.findByScanCode(qrCodeUrl);
            TransactionData queryTrans = new TransactionData();
            BeanUtils.copyProperties(transactionData,queryTrans);
            queryTrans.setTranType("G");
            String transParam = gson.toJson(queryTrans);
            Callable<String> task = new Callable<String>() {
                @Override
                public String call() throws Exception {

                    String result = "";
                    while (true){
                        result = restTemplate.doPostBankApi(transParam,"");
                        TransactionData transactionData = gson.fromJson(result,TransactionData.class);
                        if("00".equals(transactionData.getRespCode())){
                            return result;
                        }
                    }
                }
            };


            Future<String> future = executorService.submit(task);
            String result = future.get(10, TimeUnit.SECONDS);
            TransactionData transactionResultData = gson.fromJson(result,TransactionData.class);
//            QueryResult queryResult = new QueryResult();
//            BeanUtils.copyProperties(transactionResultData,queryResult);
//            queryResultRepository.save(queryResult);
            if("00".equals(transactionResultData.getRespCode())){
                linstener.success(transactionResultData);
            }else {
                linstener.error(transactionResultData);
            }

        } catch (Exception e) {
//            QueryResult queryResult = queryResultRepository.getOne("4028e5a075cf2a0a0175cf2a6d590003");
//            TransactionData transactionResultData = new TransactionData();
//            BeanUtils.copyProperties(queryResult,transactionResultData);
//            linstener.success(transactionResultData);
            linstener.exception(e.getMessage());
        }finally {
            executorService.shutdown();
        }

    }

    @Override
    public void save(String merTradeNo, ResultListener linstener) {
        TransactionData transactionData = transactionRepository.findByMerTradeNoAndTranType(merTradeNo,"F");
        ResponseData<String> responseData = new ResponseData<>();
        if(transactionData==null){
            responseData.setCode("500");
            responseData.setData("ERROR");
            responseData.setMessage(String.format("根据商户订单号%s未查询到交易记录",merTradeNo));
            linstener.error(responseData);
            return;
        }
        Gson gson = new Gson();
        String cardNo = transactionData.getCardNo();
        String tradNo = transactionData.getTradeNo();
        SortedMap<String, String> packageParams = new TreeMap<String, String>();
        Patient patient = patientRepository.getOne(cardNo);
        packageParams.put("cardID", cardNo);
        packageParams.put("money", "1");
        packageParams.put("modeType", "1");
        packageParams.put("operatorid", "0102");
        packageParams.put("patientName", patient.getName());
        packageParams.put("serialNumber", merTradeNo);
        packageParams.put("token", token);
        packageParams.put("hosId", hosId);
        String sign = HisMd5Sign.createSign(packageParams, token);
        packageParams.put("sign", sign);
        String param = gson.toJson(packageParams);


        try {
           String result = restTemplate.doPostHisSaveApi(param,"his/payMedicalCard");
           if(StringUtils.isEmpty(result)){
               responseData.setCode("500");
               responseData.setData("ERROR");
               responseData.setMessage("HIS系统无返回值");
               linstener.error(responseData);
               return;
           }
            ResponseHisData<Object> responseHisData = gson.fromJson(result,ResponseHisData.class);

            if(responseHisData.getCode()==0){
                responseData.setCode("200");
                responseData.setData("SUCCESS");
                responseData.setMessage("success");
                linstener.success(responseData);
            }else{
                responseData.setCode("500");
                responseData.setData("ERROR");
                responseData.setMessage(String.format("HIS系统充值失败，获取HIS返回信息:%s",responseHisData.getResponseData()));
                linstener.error(responseData);
            }
        } catch (Exception e) {
            responseData.setCode("500");
            responseData.setData("ERROR");
            responseData.setMessage(e.getMessage());
            linstener.exception(responseData);
        }

    }

    /**
     * 住院预交金
     * @param tradeNo
     * @param linstener
     */
    @Override
    public void saveHospitalized(String tradeNo, ResultListener linstener) {
        TransactionData transactionData = transactionRepository.findByMerTradeNoAndTranType(tradeNo,"F");
        ResponseData<String> responseData = new ResponseData<>();
        if(transactionData==null){
            responseData.setCode("500");
            responseData.setData("ERROR");
            responseData.setMessage(String.format("根据商户订单号%s未查询到交易记录",tradeNo));
            linstener.error(responseData);
            return;
        }
        Gson gson = new Gson();
        String cardNo = transactionData.getCardNo();
        String tradNo = transactionData.getTradeNo();
        Map<String,Object> paramMap = new HashMap<>();
        paramMap.put("cardID",cardNo);
        paramMap.put("money","1");
        paramMap.put("modeType","3");
        paramMap.put("serialNumber",tradNo);

        try {
            String result = restTemplate.doPostHisApi(paramMap,"his/paymenPrepaid");
            if(StringUtils.isEmpty(result)){
                responseData.setCode("500");
                responseData.setData("ERROR");
                responseData.setMessage("HIS系统无返回值");
                linstener.error(responseData);
                return;
            }
            ResponseHisData<Object> responseHisData = gson.fromJson(result,ResponseHisData.class);

            if(responseHisData.getCode()==0){
                responseData.setCode("200");
                responseData.setData("SUCCESS");
                responseData.setMessage("success");
                linstener.success(responseData);
            }else{
                responseData.setCode("500");
                responseData.setData("ERROR");
                responseData.setMessage(String.format("HIS系统充值失败，获取HIS返回信息:%s",responseHisData.getResponseData()));
                linstener.error(responseData);
            }
        } catch (Exception e) {
            responseData.setCode("500");
            responseData.setData("ERROR");
            responseData.setMessage(e.getMessage());
            linstener.exception(responseData);
        }

    }

    public static void main(String[] args) {
        Callable<String> task = new Callable<String>() {
            @Override
            public String call() throws Exception {

                Thread.sleep(10000);
                return "success";
            }
        };
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        Future<String> future = executorService.submit(task);

        String result = null;
        try {
            result = future.get(15, TimeUnit.SECONDS);
            System.out.println(result);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }

     }
}