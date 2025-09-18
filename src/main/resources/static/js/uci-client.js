(function (global) {
    'use strict';

    const normalizeScore = (parts, startIndex) => {
        const type = parts[startIndex + 1];
        const valueToken = parts[startIndex + 2];
        if (!type || !valueToken) {
            return null;
        }
        const score = { type: type.toLowerCase() };
        if (score.type === 'cp') {
            score.value = parseInt(valueToken, 10);
        } else if (score.type === 'mate') {
            score.value = parseInt(valueToken, 10);
        } else {
            score.value = valueToken;
        }

        let offset = startIndex + 3;
        while (offset < parts.length) {
            const token = parts[offset];
            if (token === 'lowerbound' || token === 'upperbound') {
                score.bound = token;
            } else if (token === 'currmove') {
                break;
            }
            offset += 1;
        }

        return score;
    };

    class UciClient {
        constructor(url) {
            this.url = url;
            this.ws = null;
            this.handlers = {};
            this.queue = [];
            this.isOpen = false;
            this.awaitingReady = false;
            this.awaitingBestmove = false;
            this.isReady = false;

            this._connect();
        }

        _connect() {
            this.ws = new WebSocket(this.url);
            this.ws.addEventListener('open', () => {
                this.isOpen = true;
                this.emit('open');
                this.send('uci');
                this.send('isready', { expectReady: true });
            });
            this.ws.addEventListener('message', (event) => this._handleMessage(event.data));
            this.ws.addEventListener('close', () => {
                this.emit('close');
                this.isOpen = false;
            });
            this.ws.addEventListener('error', (err) => this.emit('error', err));
        }

        on(event, handler) {
            if (!this.handlers[event]) {
                this.handlers[event] = [];
            }
            this.handlers[event].push(handler);
            return () => this.off(event, handler);
        }

        off(event, handler) {
            if (!this.handlers[event]) {
                return;
            }
            this.handlers[event] = this.handlers[event].filter((fn) => fn !== handler);
        }

        emit(event, payload) {
            (this.handlers[event] || []).forEach((handler) => {
                try {
                    handler(payload);
                } catch (err) {
                    console.error('UCI client handler failed', err);
                }
            });
        }

        send(command, options = {}) {
            this.queue.push({ command, options });
            this._processQueue();
        }

        cancelPendingSearch() {
            if (!this.awaitingBestmove) {
                return;
            }
            this.awaitingBestmove = false;
            this.queue.unshift({ command: 'stop', options: {} });
            this._processQueue();
        }

        _sendRaw(command) {
            if (this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(command);
            } else {
                console.warn('Attempted to send on a closed WebSocket', command);
            }
        }

        _processQueue() {
            if (!this.isOpen || this.awaitingReady || this.awaitingBestmove) {
                return;
            }

            const next = this.queue.shift();
            if (!next) {
                return;
            }

            const { command, options } = next;
            this._sendRaw(command);

            if (options.awaitBestmove) {
                this.awaitingBestmove = true;
                return;
            }

            if (options.expectReady) {
                this.awaitingReady = true;
                this.isReady = false;
                return;
            }

            if (options.awaitReady) {
                this.queue.unshift({ command: 'isready', options: { expectReady: true } });
                return;
            }

            this._processQueue();
        }

        _handleMessage(data) {
            const lines = data.split('\n');
            lines.forEach((line) => {
                const trimmed = line.trim();
                if (!trimmed) {
                    return;
                }
                this.emit('raw', trimmed);
                if (trimmed === 'uciok') {
                    this.emit('uciok');
                    return;
                }
                if (trimmed === 'readyok') {
                    this.awaitingReady = false;
                    this.isReady = true;
                    this.emit('readyok');
                    this._processQueue();
                    return;
                }
                if (trimmed.startsWith('id ')) {
                    const [, type, ...rest] = trimmed.split(' ');
                    this.emit('id', { type, value: rest.join(' ') });
                    return;
                }
                if (trimmed.startsWith('option ')) {
                    const option = this._parseOption(trimmed);
                    this.emit('option', option);
                    return;
                }
                if (trimmed.startsWith('info ')) {
                    const info = this._parseInfo(trimmed);
                    this.emit('info', info);
                    return;
                }
                if (trimmed.startsWith('bestmove')) {
                    const payload = this._parseBestmove(trimmed);
                    this.awaitingBestmove = false;
                    this.emit('bestmove', payload);
                    this._processQueue();
                    return;
                }
            });
        }

        _parseOption(line) {
            const option = { raw: line };
            const tokens = line.split(' ');
            let index = 1;
            while (index < tokens.length) {
                const key = tokens[index];
                if (key === 'name') {
                    let nameIndex = index + 1;
                    const nameParts = [];
                    while (nameIndex < tokens.length && tokens[nameIndex] !== 'type') {
                        nameParts.push(tokens[nameIndex]);
                        nameIndex += 1;
                    }
                    option.name = nameParts.join(' ');
                    index = nameIndex;
                    continue;
                }
                if (key === 'type') {
                    option.type = tokens[index + 1];
                    index += 2;
                    continue;
                }
                if (key === 'default') {
                    option.default = tokens[index + 1];
                    index += 2;
                    continue;
                }
                if (key === 'min') {
                    option.min = tokens[index + 1];
                    index += 2;
                    continue;
                }
                if (key === 'max') {
                    option.max = tokens[index + 1];
                    index += 2;
                    continue;
                }
                if (key === 'var') {
                    if (!option.var) {
                        option.var = [];
                    }
                    option.var.push(tokens[index + 1]);
                    index += 2;
                    continue;
                }
                index += 1;
            }
            return option;
        }

        _parseInfo(line) {
            const tokens = line.split(' ');
            const info = { raw: line };
            for (let i = 1; i < tokens.length; i += 1) {
                const token = tokens[i];
                switch (token) {
                    case 'depth':
                        info.depth = parseInt(tokens[i + 1], 10);
                        i += 1;
                        break;
                    case 'seldepth':
                        info.seldepth = parseInt(tokens[i + 1], 10);
                        i += 1;
                        break;
                    case 'time':
                        info.time = parseInt(tokens[i + 1], 10);
                        i += 1;
                        break;
                    case 'nodes':
                        info.nodes = parseInt(tokens[i + 1], 10);
                        i += 1;
                        break;
                    case 'pv':
                        info.pv = tokens.slice(i + 1);
                        i = tokens.length; // exit loop
                        break;
                    case 'score':
                        info.score = normalizeScore(tokens, i);
                        i += 2;
                        break;
                    case 'multipv':
                        info.multipv = parseInt(tokens[i + 1], 10);
                        i += 1;
                        break;
                    case 'currmove':
                        info.currmove = tokens[i + 1];
                        i += 1;
                        break;
                    default:
                        break;
                }
            }
            return info;
        }

        _parseBestmove(line) {
            const parts = line.split(' ');
            return {
                raw: line,
                move: parts[1],
                ponder: parts[3] || null,
            };
        }
    }

    global.UciClient = UciClient;
}(window));
