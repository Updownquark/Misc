
type Listener = () => any;

export const LifeCycleStage = {
	PreInit: "Pre-Init",
	Initializing: "Initializing",
	Active: "Active",
	ShuttingDown: "Shutting Down",
	Dead: "Dead"
} as const;
export type LifeCycleStage=typeof LifeCycleStage [keyof typeof LifeCycleStage];

class DoAfterInactivity{
	constructor(
		public doNextAt: number,
		private readonly action: Listener,
	){}

	maybeRun(): number {
		const now=Date.now();
		if(now>=this.doNextAt)
			this.action();
		return this.doNextAt-now;
	}
}

class LifeCycleService{
	private static readonly CLONE_TAB_DETECTION="vista_tab_";

	private readonly _isNewTab: boolean;
	private _stage: LifeCycleStage=LifeCycleStage.PreInit;
	private _initSubscribers = new Set<Listener>();
	private _heartBeatSubscribers = new Set<Listener>();
	private _shutdownSubscribers = new Set<Listener>();
	private _intervalId: ReturnType<typeof setInterval> | null = null;
	private _isBeating: boolean = false;
	private _beatIntervalMs: number;
	private readonly _inactivityActions=new Map<Object, DoAfterInactivity>();

	constructor(syncIntervalMs=60_000){
		this._beatIntervalMs=syncIntervalMs;

		//Detect tab cloning to prevent unique resource re-use
		if(!window.name){
			this._isNewTab=true;
			window.name=LifeCycleService.CLONE_TAB_DETECTION+crypto.randomUUID;
		} else
			this._isNewTab=window.name.indexOf(LifeCycleService.CLONE_TAB_DETECTION)<0;
	}
	
	public getStage (): LifeCycleStage {
		return this._stage;
	}

	public isBeating (): boolean {
		return this._isBeating;
	}

	public isNewTab(): boolean {
		return this._isNewTab;
	}

	public onInit(callback: Listener): () => void {
		switch(this._stage){
			case LifeCycleStage.PreInit:
			case LifeCycleStage.Initializing:
				this._initSubscribers.add(callback);
				return ()=>this._initSubscribers.delete(callback);
			default:
				// Already initialized, so this should be safe
				callback();
				break;
		}
	}

	public onHeartBeat (callback: Listener): () => void {
		switch(this._stage){
			case LifeCycleStage.PreInit:
			case LifeCycleStage.Initializing:
			case LifeCycleStage.Active:
				this._heartBeatSubscribers.add(callback);
				return ()=>this._heartBeatSubscribers.delete(callback);
			default:
				console.warn("VISTA is not active");
				break;
		}
	}

	public onShutdown (callback: Listener): () => void {
		//Don't bother checking this
		this._shutdownSubscribers.add(callback);
		return ()=>this._shutdownSubscribers.delete(callback);
	}

	public async beat (): Promise<boolean> {
		if(this._isBeating)
			return false;
		else if(this._stage!=LifeCycleStage.Active)
			throw "The heart cannot beat during stage "+this._stage;
		
		this._isBeating=true;
		try{
			this._heartBeatSubscribers.forEach((callback) =>{
				try{
					callback();
				} catch(error){
					console.error("Heart beat callback ", callback, " did not execute successfully: ", error)
				}
			});
		} finally{
			this._isBeating=false;
		}
		return true;
	}
	
	public setBeatInterval(syncIntervalMs: number): void {
		if(this._beatIntervalMs==syncIntervalMs)
			return;
		
		this._beatIntervalMs=syncIntervalMs;
		if(this._intervalId){
			clearInterval(this._intervalId);
			this._intervalId = setInterval(()=>this.beat(), this._beatIntervalMs);
		}
	}

	public doAfterInactivity(key: any, wait: number, action: Listener){
		const now=Date.now();
		let dai=this._inactivityActions.get(key);
		if(dai)
			dai.doNextAt=now+wait;
		else{
			dai=new DoAfterInactivity(now+wait, action);
			this._inactivityActions.set(key, dai);
			const timeout=()=>{
				const nextRun=dai.maybeRun();
				if(nextRun>0)
					setTimeout(timeout, nextRun+5);
				else
					this._inactivityActions.delete(key);
			};
			setTimeout(timeout, wait+5);
		}
	}
	
	public async start() {
		if(this._stage!=LifeCycleStage.PreInit)
			return; //The lifecycle should not be started twice
		
		this._stage=LifeCycleStage.Initializing;
		await this.callSubscribers(this._initSubscribers);
		this._initSubscribers=null;
		
		this._stage=LifeCycleStage.Active;
		
		this.beat();
		
		this._intervalId = setInterval(()=>this.beat(), this._beatIntervalMs);
	}

	private async callSubscribers(subs: Set<Listener>){
		for(const sub of subs){
			try{
				const ret=sub();
				if(ret//
					 && (typeof ret === "object" || typeof ret === "function")//
					 && typeof ret.then === "function") //Returned a promise.  Let it run before continuing.
						await (ret as Promise<any>);
			} catch(error){
				console.error("Initializer callback ", sub, " did not execute successfully: ", error)
			}
		}
	}
	
	public stop(): void {
		switch(this._stage){
			case LifeCycleStage.PreInit:
			case LifeCycleStage.Initializing:
				return; // Life cycle has not started
			case LifeCycleStage.ShuttingDown:
			case LifeCycleStage.Dead:
				return; // Life cycle has already ended
		}
		
		this._stage=LifeCycleStage.ShuttingDown;
		clearInterval(this._intervalId);
		this._intervalId=null;

		for(const sub of this._shutdownSubscribers){
			try{
				sub();
			} catch(error){
				console.error("Shutdown callback ", sub, " did not execute successfully: ", error)
			}
		}
		this._stage=LifeCycleStage.Dead;
	}
}

export default LifeCycleService;
