package deepDriver.dl.aml.lstm.distribution;

import java.io.File;


import deepDriver.dl.aml.distribution.AsycMaster;
import deepDriver.dl.aml.distribution.Error;
import deepDriver.dl.aml.distribution.Fs;
import deepDriver.dl.aml.lrate.BoldDriverLearningRateManager;
import deepDriver.dl.aml.lrate.LearningRateManager;
import deepDriver.dl.aml.lstm.GradientNormalizer;
import deepDriver.dl.aml.lstm.LSTMCfgCleaner;
import deepDriver.dl.aml.lstm.LSTMConfigurator;
import deepDriver.dl.aml.lstm.LSTMDeltaWwFromWwUpdater;
import deepDriver.dl.aml.lstm.LSTMDeltaWwUpdater;
import deepDriver.dl.aml.lstm.LSTMWwFresher;
import deepDriver.dl.aml.lstm.LSTMWwUpdater;
import deepDriver.dl.aml.lstm.Seq2SeqLSTM;
import deepDriver.dl.aml.lstm.Seq2SeqLSTMConfigurator;
import deepDriver.dl.aml.lstm.data.LSTMCfgData;
import deepDriver.dl.aml.sa.SA;
import deepDriver.dl.aml.string.Dictionary;

public class Seq2SeqAsycMasterV6 extends AsycMaster {
	Dictionary dic;
	Seq2SeqLSTM srvQ2QLSTM;	
	public Seq2SeqAsycMasterV6() {
		
	}
	Seq2SeqLSTMBoostrapper boot;
	public Seq2SeqAsycMasterV6(int clientsNum, Seq2SeqLSTMBoostrapper boot) throws Exception {
		super();
		this.boot = boot;
		boot.bootstrap(null, true);
		this.dic = boot.getDic();
		this.srvQ2QLSTM = boot.getSeq2SeqLSTM();
		this.clientsNum = clientsNum;
		taskNum = dic.getLineNum();
		srvQ2QLSTM.getCfg().setTestQ(true);
	}

	int taskNum = 4002;
	int clientsNum = 4;
	
	boolean measureOnly = false;
	
	LSTMWwUpdater wWUpdater = new LSTMWwUpdater(false, true);
	LSTMWwUpdater deltaWwUpdater = new LSTMWwUpdater(false, false);

	public Seq2SeqAsycMasterV6(int clientsNum, Seq2SeqLSTMBoostrapper boot,
			String sfile) throws Exception {
		this(clientsNum, boot);
		if (sfile != null) {
			System.out.println("Start with mfile->"+sfile);
			Seq2SeqLSTMConfigurator cfg = (Seq2SeqLSTMConfigurator) Fs.readObjFromFile(sfile);
//			wWUpdater.updatewWs(cfg.getQlSTMConfigurator(), srvQ2QLSTM.getCfg().getQlSTMConfigurator());
//			wWUpdater.updatewWs(cfg.getAlSTMConfigurator(), 
//					srvQ2QLSTM.getCfg().getAlSTMConfigurator());
			
			copyWws(cfg.getQlSTMConfigurator(), srvQ2QLSTM.getCfg().getQlSTMConfigurator());
			copyWws(cfg.getAlSTMConfigurator(), srvQ2QLSTM.getCfg().getAlSTMConfigurator());

			/*** 
			double ml = 0.001;
			if (srvQ2QLSTM.getCfg().getAlSTMConfigurator().getLearningRate() < ml) {
				srvQ2QLSTM.getCfg().getAlSTMConfigurator().setLearningRate(ml);
			}***/
			
			this.srvQ2QLSTM.setTrainCfg(srvQ2QLSTM.getCfg());
		}
		srvQ2QLSTM.getCfg().getQlSTMConfigurator().setMeasureOnly(measureOnly);
		srvQ2QLSTM.getCfg().getAlSTMConfigurator().setMeasureOnly(measureOnly);
	}
	
	public static int QType = 1;
	public static int AType = 2;	
	LSTMConfigurator cfgFromClient;
	public int getClientsNum() {
		return clientsNum;
	}
	SimpleTask [] tasks;
	int batchSize = 64;
	int tq = 0;
		
	public static boolean isQMode(LSTMCfgData lstmCfgFromClient) {
		if (lstmCfgFromClient.getType() == QType) {
			return true;
		}
		return false;
	}
	
	public static boolean isQMode(LSTMConfigurator lstmCfgFromClient) {
		if (lstmCfgFromClient.getType() == QType) {
			return true;
		}
		return false;
	}
	
	Verifier verifier = new Verifier(false);
	
	public Object getDistributeSubject() {
//		System.out.println("Prepare srv cfg to clients"+srvQ2QLSTM.getCfg().getLoop());
		LSTMConfigurator cfg = srvQ2QLSTM.getCfg().getQlSTMConfigurator();
		int type = QType;
		if (srvQ2QLSTM.getCfg().isTestQ()) {
		} else {
			cfg = srvQ2QLSTM.getCfg().getAlSTMConfigurator();
			type = AType;
		}
		
		verifier.cfgDistributeWws(cfg);
		
		if (cfgFromClient == null) {
			cfgFromClient = cfg;			
		} else {
			copyWws(cfg, cfgFromClient);
		}/*****/  
		cfgFromClient.setType(type);
		cfgFromClient.setLoop(srvQ2QLSTM.getCfg().getLoop());
		int loop = tq/batchSize;		
		System.out.println("Ready for srv cfg with "+cfgFromClient.getLoop()+" rounds");
		return cfgFromClient;
	}
	
	public void copyLM(LSTMConfigurator fcfg, LSTMConfigurator t2cfg) {
		t2cfg.setM(fcfg.getM());
		t2cfg.setLearningRate(fcfg.getLearningRate());
	}
	
	public void copyWws(LSTMConfigurator fcfg, LSTMConfigurator t2cfg) {
		wWUpdater.updatewWs(fcfg, t2cfg);	
		deltaWwUpdater.setResetDeltaWw(true);
		deltaWwUpdater.updatewWs(fcfg, t2cfg);
		t2cfg.setM(fcfg.getM());
		t2cfg.setLearningRate(fcfg.getLearningRate());
//		t2cfg.setM(0);
//		t2cfg.setLearningRate(1);
		t2cfg.setBatchSize4DeltaWw(fcfg.isBatchSize4DeltaWw());
		t2cfg.setMeasureOnly(fcfg.isMeasureOnly());
	}
	
	GradientNormalizer gn = new GradientNormalizer();
	
	double threshold = 50;
	
	double dl = 1;
	int lCycle = 3;
	protected double getL(LSTMConfigurator cfg) {
		double nl = cfg.getLearningRate();
		int a = epichNum / lCycle;
		nl = dl;
		if (a > 0) {
			double d = Math.pow(2, a);
//			nl = dl/(5.0 * (double)(a));
			nl = dl/d;
		}		
		System.out.println("" +"Learn with M"+cfg.getM()+", L"+nl);
		return nl;
	}
	
	LSTMDeltaWwFromWwUpdater batchDeltaWwUpdaterV2 = new LSTMDeltaWwFromWwUpdater();
	LSTMDeltaWwUpdater batchDeltaWwUpdater = new LSTMDeltaWwUpdater();
//	LSTMDeltaWwFromWwUpdater batchDeltaWwUpdater = new LSTMDeltaWwFromWwUpdater();
	
	LSTMWwFresher lSTMWwFresher = new LSTMWwFresher();
	LSTMWwUpdater wWchecker = new LSTMWwUpdater(true, false);
	
	LSTMCfgCleaner lSTMCfgCleaner = new LSTMCfgCleaner();
	
	public boolean isCltSrvSameMode(Object [] objs) {		
		if (objs.length > 0 && isQMode((LSTMConfigurator)objs[0]) != srvQ2QLSTM.getCfg().isTestQ()) {
			System.out.println("Clients and server are not running in the same mode, return");
			return false;
		}
		return true;
	}

	public synchronized void mergeSubject(Object[] objs) {
//		System.out.println("Prepare to merge Ww from clients");
		LSTMConfigurator[] cfgs = new LSTMConfigurator[objs.length];
		for (int i = 0; i < cfgs.length; i++) {
			cfgs[i] = (LSTMConfigurator) objs[i]; 
		}
		
		srvQ2QLSTM.getCfg().setLoop(srvQ2QLSTM.getCfg().getLoop() + 1); 
		System.out.println("Complete "+srvQ2QLSTM.getCfg().getLoop()+" loops over all.");
		LSTMConfigurator cfg = srvQ2QLSTM.getCfg().getQlSTMConfigurator();

		if (srvQ2QLSTM.getCfg().isTestQ()) {			
		} else {
			cfg = srvQ2QLSTM.getCfg().getAlSTMConfigurator();
		}	
		
//		double l = getL(cfg);
		
//		batchDeltaWwUpdaterV2.mergeDeltawWs(cfgs, cfg, l, cfg.getM());
//		batchDeltaWwUpdaterV2.mergeDeltawWs(cfgs, cfg);
		//clients caculate the correct delta wWs, so server do not need to recaculate again
		//just consolidating them together will be ok.
		batchDeltaWwUpdater.mergeDeltawWs(cfgs, cfg, 1, 0);
		gn.normGradient(cfg, threshold);
		lSTMWwFresher.freshwWs(cfg);
				
		if (cfgFromClient != srvQ2QLSTM.getCfg().getQlSTMConfigurator()
				&& cfgFromClient != srvQ2QLSTM.getCfg().getAlSTMConfigurator()) {
			lSTMCfgCleaner.clean(cfgFromClient);
		}		
		cfgFromClient = cfgs[0];
		cfgs[0] = null; 
		for (int i = 1; i < cfgs.length; i++) {
			lSTMCfgCleaner.clean(cfgs[i]);
			cfgs[i] = null;
		}
		lSTMCfgCleaner.gbClean();
		
		verifier.verifyMergeWws(cfg);
	}
	

	double lastError = 0;
	boolean isM = false; 
	
	boolean neverAdjust = true;
	double m = 0;
	
	SA sa = new SA(10000, 0.8, false, -1);
	LearningRateManager lrm = new BoldDriverLearningRateManager();
	
	private void adjustML(double err, LSTMConfigurator cfg) {
		if (lrm != null) {
			double l = lrm.adjustML(err, cfg.getLearningRate());
			if (l > 0.00001) {//no need to adjust any more
				cfg.setLearningRate(l);
			}			
			System.out.println("Distribute M"+cfg.getM()+", L"+cfg.getLearningRate());
			return;
		}
		if (neverAdjust) {
			return ;
		}
//		if (m == 0) {
//			m = cfg.getM();
//		}
//		cfg.setM(m);
		boolean sab = sa.sa(err - lastError);
		if (lastError > 0 && err > lastError) {
//			cfg.setM(0);			
			if (cfg.getM() > 0 && isM) {
//				cfg.m = cfg.m / 3.0 * 2.0;				
				cfg.setM(cfg.getM() / 3.0 * 2.0);
				System.out.println("Adjust M");
				System.out.println("Distribute M"+cfg.getM()+", L"+cfg.getLearningRate());

			} else {
//				cfg.learningRate = cfg.learningRate / 3.0 * 2.0;
				if (!sab) {
					cfg.setLearningRate(cfg.getLearningRate()/ 3.0 * 2.0);
					System.out.println("Adjust L");
				} else {
					System.out.println("No need to adjust L");
				}				
			} 
			//do not adjust M
//			isM = !isM;		
		} else {
			cfg.setLearningRate(cfg.getLearningRate() * 1.05);
			System.out.println("Adjust L");
		}
		System.out.println("Distribute M"+cfg.getM()+", L"+cfg.getLearningRate());
		lastError = err;
	}
	
	int epichNum = 0;
	int epichLoop = 10;
	
	double epichError = 0;
	int epichReadyClient = 0;
	public double caculateErrorLastTime(Object[] objs) {
		double err = 0;
		double cnt = 0;		
		Error ce1 = (Error)objs[0];
		if (!ce1.isReady()) {
			return 0;
		}
		
		for (int i = 0; i < objs.length; i++) {
			Error ce = (Error)objs[i];
//			if (!ce.isReady()) {
//				return 0;
//			}
			err = err + ce.getErr();
			cnt = cnt + 1;//+ ce.getCnt();
		}
		epichReadyClient = epichReadyClient + objs.length;
		epichError = epichError + err;
		System.out.println(""+epichReadyClient+"/"+this.clientsNum+" clients are ready ");
		if (epichReadyClient >= this.clientsNum) {
//			System.out.println(""+epichReadyClient+" clients are ready.");
			err = epichError/((double)epichReadyClient);
			cnt = epichReadyClient;
			epichError = 0;
			epichReadyClient = 0;
		} else {			
			return 0;
		}
		epichNum ++;		
		if (srvQ2QLSTM.getCfg().isTestQ()) {
			if (err <= srvQ2QLSTM.getCfg().getQlSTMConfigurator().getAccuracy()) {
				srvQ2QLSTM.getCfg().setTestQ(false);
				System.out.println("Switch to A mode, and reset the cfg");
				cfgFromClient = null;
				lastError = 0;
				isM = false;
				System.out.println("Complete Q lstm training.");
				save2File();
				
				epichNum = 1; 
				sa.reset();
				
			} else {
				save2File((epichNum % epichLoop)+"Q");
				adjustML(err, srvQ2QLSTM.getCfg().getQlSTMConfigurator());
			}
			System.out.println(cnt+" is tested Q with error "+ err);
		} else {
			if (err <= srvQ2QLSTM.getCfg().getAlSTMConfigurator().getAccuracy()) {
				this.done = true;
				save2File();
				System.out.println("Complete A lstm training");
			} else {
				save2File((epichNum % epichLoop)+"A");
				adjustML(err, srvQ2QLSTM.getCfg().getAlSTMConfigurator());
			}
			System.out.println(cnt+" is tested A with error "+ err);
		}				
		return err;
	}
	
	String cfgFileName = "seq2seqCfg";
	private void save2File() {
		save2File(null);		
	}
	long currentTimestamp = System.currentTimeMillis();
	private void save2File(String middleName) {
		String sf = System.getProperty("user.dir");		
		File dir = new File(sf, "data");
		dir.mkdirs();		
		File f = null;
		if (middleName == null) {
			f = new File(dir, cfgFileName+"_"+currentTimestamp+".m");
		} else {
			f = new File(dir, cfgFileName+"_"+currentTimestamp+
					"_"+middleName+".m");
		}		
		try {
			Fs.writeObj2FileWithTs(f.getAbsolutePath(), srvQ2QLSTM.getCfg());
			System.out.println("Save "+cfgFileName+" into "+f.getAbsolutePath());
		} catch (Exception e) {
			e.printStackTrace();
		}		
	}

	@Override
	public void testOnMaster() throws Exception {
		// TODO Auto-generated method stub
		
	}

	public Object[] splitTasks() {
//		System.out.println("Prepare to assign task to clients "+clientsNum);
		if (tasks == null) {
			System.out.println("There are "+taskNum +" tasks, and handled by "+clientsNum+" clients");
		}		
		tasks = new SimpleTask[clientsNum];
		tq = taskNum / clientsNum;
//		batchSize = tq;
		int left = taskNum - tq * clientsNum;
		
		for (int i = 0; i < tasks.length; i++) {
			int l = 0;
			if (left >= i+1) {
				l = 1;
			}
			if (i == 0) {
				tasks[i] = new SimpleTask(1, tq + l, batchSize);
			} else {
				tasks[i] = new SimpleTask(
						tasks[i - 1].getEnd()+ 1, tasks[i - 1].getEnd() + tq + l, batchSize);
			}			
		}
//		System.out.println("done ."+clientsNum);
		this.srvQ2QLSTM.getCfg().getQlSTMConfigurator().setMBSize(batchSize);
		this.srvQ2QLSTM.getCfg().getAlSTMConfigurator().setMBSize(batchSize);		
		return tasks;
	}
}
