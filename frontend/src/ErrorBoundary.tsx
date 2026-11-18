import {Component,type ErrorInfo,type ReactNode} from 'react';

type State={error:Error|null};

/** The reporting workspace must display a recoverable error, not a blank page. */
export default class ErrorBoundary extends Component<{children:ReactNode},State>{
  state:State={error:null};

  static getDerivedStateFromError(error:Error):State{return {error};}

  componentDidCatch(error:Error,info:ErrorInfo){
    // No PII, financial values or payloads are logged.
    console.error('FIN React rendering failure',error.name,info.componentStack);
  }

  render(){
    if(!this.state.error)return this.props.children;
    return <main role="alert" style={{
      fontFamily:'Segoe UI,Arial,sans-serif',maxWidth:650,margin:'12vh auto',
      padding:28,background:'#fff',color:'#293a54',border:'1px solid #dfe5f0',
      borderRadius:10
    }}>
      <h1 style={{fontSize:20}}>FIN Form Studio — ошибка интерфейса</h1>
      <p>Отчёт не был автоматически сохранён. Проверьте соединение с Backend и
        откройте документ заново перед повторной записью.</p>
      <p style={{fontFamily:'monospace',fontSize:12}}>
        {this.state.error.name}: {this.state.error.message}
      </p>
      <button type="button" onClick={()=>window.location.reload()}
        style={{padding:'10px 15px',borderRadius:6,border:'1px solid #afbfdd',
          background:'#eaf1ff',color:'#23437c',cursor:'pointer'}}>
        Перезагрузить интерфейс
      </button>
    </main>;
  }
}
