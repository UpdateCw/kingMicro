package com.king.rest.oss;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.king.api.oss.OssDoc2pdfService;
import com.king.api.smp.SysConfigService;
import com.king.common.annotation.Log;
import com.king.common.utils.JsonResponse;
import com.king.common.utils.Page;
import com.king.common.utils.constant.ConfigConstant;
import com.king.common.utils.exception.RRException;
import com.king.common.utils.file.IoUtil;
import com.king.common.utils.pattern.StringToolkit;
import com.king.dal.gen.model.oss.CloudStorageConfig;
import com.king.dal.gen.model.oss.OssDoc2pdf;
import com.king.utils.AbstractController;
import com.king.utils.Query;
import com.king.utils.cloud.CloudStorageService;
import com.king.utils.cloud.DocConverter;
import com.king.utils.cloud.OSSFactory;
import com.king.utils.pattern.XssHttpServletRequestWrapper;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * office、png转pdf、pdf生成图片
 * 
 * @author King chen
 * @emai 396885563@qq.com
 * @data 2018年7月19日
 */
@Lazy
@RestController
@Api(value = "pdf转换服务", description = "pdf转换服务")
@RequestMapping("/oss/pdf")
public class OssPdfController extends AbstractController {
	@Autowired
	private OssDoc2pdfService ossDoc2pdfService;
	@Autowired
	private SysConfigService sysConfigService;
//	BlockingQueue<String> queue = new LinkedBlockingQueue<String>(10);
	BlockingQueue<Map> queue = new LinkedBlockingQueue<Map>(20);

	/**
	 * 列表
	 */
	@ApiOperation(value = "列表", notes = "权限编码（oss:pdf:list）")
	@GetMapping("/list")
	@RequiresPermissions("oss:pdf:list")
	public JsonResponse list(@RequestParam Map<String, Object> params) {
		// 查询列表数据
		Query query = new Query(params, OssDoc2pdf.class.getSimpleName());
		Page page = ossDoc2pdfService.getPage(query);
		return JsonResponse.success(page);
	}

	/**
	 * 信息
	 */
	@Log("文件上传查询信息")
	@ApiOperation(value = "查询信息", notes = "权限编码（oss:pdf:info）")
	@GetMapping("/info/{id}")
	@RequiresPermissions("oss:pdf:info")
	public JsonResponse info(@PathVariable("id") Object id) {
		OssDoc2pdf ossDoc2pdf = ossDoc2pdfService.queryObject(id);

		return JsonResponse.success(ossDoc2pdf);
	}

	/**
	 * 修改
	 */
	@Log("文件上传修改")
	@ApiOperation(value = "修改", notes = "权限编码（oss:pdf:update）")
	@PostMapping("/update")
	@RequiresPermissions("oss:pdf:update")
	public JsonResponse update(@RequestBody OssDoc2pdf ossDoc2pdf) {
		ossDoc2pdfService.update(ossDoc2pdf);

		return JsonResponse.success();
	}

	/**
	 * 删除本地文件并循环删除云文件
	 */
	@Log("文件上传删除")
	@ApiOperation(value = "删除", notes = "权限编码（oss:pdf:delete）")
	@PostMapping("/delete")
	@RequiresPermissions("oss:pdf:delete")
	public JsonResponse delete(@RequestBody Object[] ids) {
		CloudStorageConfig config = sysConfigService.getConfigObject(ConfigConstant.CLOUD_STORAGE_CONFIG_KEY,
				CloudStorageConfig.class);
		String yunPath = null;
		String deleteObject = null;
		List<OssDoc2pdf> list = ossDoc2pdfService.queryBatch(ids);
		for (OssDoc2pdf oss : list) {
			switch (config.getType()) {
			case 1:
				yunPath = config.getQiniuDomain() + "/";
				if (StringUtils.isNotBlank(config.getQiniuPrefix())) {
					yunPath = yunPath + config.getQiniuPrefix();
				}
				deleteObject = oss.getUrl().replace(yunPath, "");
				OSSFactory.build().delete(deleteObject);
				break;
			case 2:
				yunPath = config.getAliyunDomain() + "/";
				if (StringUtils.isNotBlank(config.getAliyunPrefix())) {
					yunPath = yunPath + config.getAliyunPrefix();
				}
				deleteObject = oss.getUrl().replace(yunPath, "");
				OSSFactory.build().delete(deleteObject);
				break;
			case 3:
				yunPath = config.getQcloudDomain() + "/";
				if (StringUtils.isNotBlank(config.getQcloudPrefix())) {
					yunPath = yunPath + config.getQcloudPrefix();
				}
				deleteObject = oss.getUrl().replace(yunPath, "");
				OSSFactory.build().delete(deleteObject);
				break;
			default:
				break;
			}
		}
		ossDoc2pdfService.deleteBatch(ids);
		return JsonResponse.success();
	}

	/**
	 * 上传文件
	 * Text documents (odt, doc, docx, rtf, etc.) 
	 * Spreadsheet documents (ods, xls, xlsx, csv, etc.)
	 * Spreadsheet documents (odp, ppt, pptx, etc.) 
	 * Drawing documents (odg, png, svg, etc.)
	 */
	@ApiOperation(value = "文件上传", notes = "权限编码（oss:pdf:upload）")
	@RequestMapping("/upload")
	@RequiresPermissions("oss:pdf:upload")
	public JsonResponse upload(@RequestParam("file") MultipartFile file) throws Exception {
		if (file.isEmpty()) {	
			return JsonResponse.error("上传文件不能为空");
		}else if(file.getOriginalFilename().endsWith("doc")||file.getOriginalFilename().endsWith("docx")||file.getOriginalFilename().endsWith("xls")
				||file.getOriginalFilename().endsWith("xlsx")||file.getOriginalFilename().endsWith("ppt")||file.getOriginalFilename().endsWith("pptx")
				||file.getOriginalFilename().endsWith("png")||file.getOriginalFilename().endsWith("svg")||file.getOriginalFilename().endsWith("rtf")){
		}else{
			return JsonResponse.error("只支持office或png格式！");
		}
		String dest= IoUtil.getFile("gen").getPath()+File.separator+file.getOriginalFilename();//存放在gen临时目录
		IoUtil.writeByteToFile(file.getBytes(), dest);
		HashMap<Object, Object> map = new HashMap<>();
		map.put("filePath", dest);
		queue.offer(map, 2, TimeUnit.SECONDS);//2秒内加入队列、否则失败
		ExecutorService service = Executors.newCachedThreadPool();	
		service.execute(new Consumer(file, dest));
		return JsonResponse.success();
	}

	public class Consumer implements Runnable {

	    private String dest;//转换文件路径
	    private MultipartFile file;
	    //构造函数
	    public Consumer(MultipartFile file,String dest) {
	        this.dest=dest;
	        this.file=file;
	    }
	 
	    public void run() {
	    	try {
	    		@SuppressWarnings("rawtypes")
				Map data=queue.poll(); 		
	    		dest= StringToolkit.getObjectString(data.get("filePath"));   	
	    		if(DocConverter.docConvertPdf(new File(dest),dest.substring(0, dest.lastIndexOf("."))+".pdf")){//转换成pdf
					File pdf= new File(dest.substring(0, dest.lastIndexOf("."))+".pdf");
					// 上传文件
					String suffix = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
					CloudStorageService cloudStorage = OSSFactory.build();// 初始化获取配置
					CloudStorageConfig config = cloudStorage.config;
					String doc_url = cloudStorage.uploadSuffix(new FileInputStream(dest), suffix);
					String pdf_url = cloudStorage.uploadSuffix(new FileInputStream(pdf), ".pdf");
					String size = new BigDecimal(file.getSize()).divide(new BigDecimal(1024), RoundingMode.HALF_UP) + " KB";
					// 保存文件信息
					OssDoc2pdf oss = new OssDoc2pdf();
					oss.setType(config.getType() + "");
					oss.setSize(size);
					oss.setUrl(doc_url);
					oss.setPdf(pdf_url);
					oss.setName(file.getOriginalFilename());
					oss.setCreator(getUser().getUsername());
					oss.setCreateDate(new Date());
					ossDoc2pdfService.save(oss);
				}
			} catch (Exception e) {
				e.printStackTrace();
				// TODO: handle exception
			}
	       
	    }
	 
	
	}

	@ApiOperation(value = "pdf预览", notes = "权限编码（oss:pdf:view）")
	@GetMapping("/view")
	@RequiresPermissions("oss:pdf:view")
	public void pdfViewer(HttpServletRequest request, HttpServletResponse response) {
		HttpServletRequest orgRequest = XssHttpServletRequestWrapper.getOrgRequest(request);//不进行xss过滤
		String urlpath = orgRequest.getParameter("urlpath");
		logger.info("urlpath=" + urlpath);
		try {
			InputStream fileInputStream = IoUtil.getFileStream(urlpath);
			response.setHeader("Content-Disposition", "attachment;fileName=test.pdf");
			response.setContentType("multipart/form-data");
			OutputStream outputStream = response.getOutputStream();
			IOUtils.write(IOUtils.toByteArray(fileInputStream), outputStream);
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

}