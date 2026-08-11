
type Listener = () => void;

export enum LifeCycleStage {
	PreInit = "Pre-Init",
	Initializing = "Initializing",
	Active = "Active",
	ShuttingDown = "Shutting Down",
	Dead = "Dead"
}

export class LifeCycleService{
	private _stage=LifeCycleStage.PreInit;
	private _initSubscribers = new Set<Listener>();
	private _heartBeatSubscribers = new Set<Listener>();
	private _shutdownSubscribers = new Set<Listener>();
	private _intervalId: ReturnType<typeof setInterval> | null = null;
	private _isBeating: boolean = false;
	private _beatIntervalMs: number;

	constructor(syncIntervalMs=10_000){
		this._beatIntervalMs=syncIntervalMs;
	}
	
	public getStage = (): LifeCycleStage => {
		return this._stage;
	}

	public isBeating = (): boolean => {
		return this._isBeating;
	}

	public onInit = (callback: Listener): () => void => {
		switch(this._stage){
			case LifeCycleStage.PreInit:
				this._initSubscribers.add(callback);
				return ()=>this._initSubscribers.delete(callback);
			case LifeCycleStage.Initializing:
				console.warn("The app is already initializing");
				break;
			default:
				console.warn("The app has already been initialized");
				break;
		}
	}

	public onHeartBeat = (callback: Listener): () => void => {
		switch(this._stage){
			case LifeCycleStage.PreInit:
			case LifeCycleStage.Initializing:
			case LifeCycleStage.Active:
				this._heartBeatSubscribers.add(callback);
				return ()=>this._heartBeatSubscribers.delete(callback);
			default:
				console.warn("The app is not active");
				break;
		}
	}

	public onShutdown = (callback: Listener): () => void => {
		//Don't bother checking this
		this._shutdownSubscribers.add(callback);
		return ()=>this._shutdownSubscribers.delete(callback);
	}

	public beat = async (): Promise<boolean> => {
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
	
	public setBeatInterval = (syncIntervalMs: number): void =>{
		if(this._beatIntervalMs==syncIntervalMs)
			return;
		
		this._beatIntervalMs=syncIntervalMs;
		if(this._intervalId){
			clearInterval(this._intervalId);
			this._intervalId = setInterval(this.beat, this._beatIntervalMs);
		}
	}
	
	public start(): void {
		if(this._stage!=LifeCycleStage.PreInit)
			return; //The lifecycle should not be started twice
		
		this._stage=LifeCycleStage.Initializing;
		this._initSubscribers.forEach((callback) =>{
			try{
				callback();
			} catch(error){
				console.error("Initializer callback ", callback, " did not execute successfully: ", error)
			}
		});
		this._initSubscribers=null;
		
		this._stage=LifeCycleStage.Active;
		
		this.beat();
		
		this._intervalId = setInterval(this.beat, this._beatIntervalMs);
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
		
		this._shutdownSubscribers.forEach((callback) =>{
			try{
				callback();
			} catch(error){
				console.error("Shutdown callback ", callback, " did not execute successfully: ", error)
			}
		});
		this._stage=LifeCycleStage.Dead;
	}
}
