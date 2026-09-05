import { LightningElement, api, wire } from 'lwc';
import { refreshApex } from '@salesforce/apex';
import findMatchesFor from '@salesforce/apex/CoalesceContactResolver.findMatchesFor';

/**
 * Shows which other contacts appear to be the same person as the one on screen, with the evidence
 * behind each claim.
 *
 * The wire result is immutable, so the template's view model is projected onto fresh objects here.
 * Presentation-only derivations -- record links, singular/plural labels, absent-attribute phrasing --
 * live in this layer so the Apex stays a resolution engine rather than a view model.
 */
export default class CoalesceDuplicates extends LightningElement {
    @api recordId;

    /** Exposed so an admin can trade breadth for CPU in App Builder without a code change. */
    @api scanLimit = 500;

    view;
    error;
    wiredResult;

    @wire(findMatchesFor, { contactId: '$recordId', scanLimit: '$scanLimit' })
    handleResult(result) {
        this.wiredResult = result;
        const { data, error } = result;
        if (data) {
            this.view = this.project(data);
            this.error = undefined;
        } else if (error) {
            this.error = this.readError(error);
            this.view = undefined;
        }
    }

    project(data) {
        return {
            ...data,
            matches: data.matches.map((pair) => this.projectPair(pair)),
            reviews: data.reviews.map((pair) => this.projectPair(pair))
        };
    }

    projectPair(pair) {
        return {
            ...pair,
            key: pair.rightId,
            link: `/lightning/r/Contact/${pair.rightId}/view`,
            absentLabel: pair.absentAttributes.length
                ? `no basis to compare: ${pair.absentAttributes.join(', ')}`
                : 'every configured attribute was comparable',
            agreedLabel: pair.agreed.join('  ')
        };
    }

    readError(error) {
        if (Array.isArray(error.body)) {
            return error.body.map((entry) => entry.message).join(', ');
        }
        return error.body?.message ?? error.message ?? 'Unknown error';
    }

    handleRefresh() {
        return refreshApex(this.wiredResult);
    }

    get loading() {
        return !this.view && !this.error;
    }

    get hasMatches() {
        return this.view?.matches?.length > 0;
    }

    get hasReviews() {
        return this.view?.reviews?.length > 0;
    }

    get isClean() {
        return this.view && !this.hasMatches && !this.hasReviews;
    }

    get matchHeadline() {
        const count = this.view?.matches?.length ?? 0;
        return count === 1
            ? 'Appears to be the same person as 1 other contact'
            : `Appears to be the same person as ${count} other contacts`;
    }

    get reviewHeadline() {
        const count = this.view?.reviews?.length ?? 0;
        return count === 1
            ? 'possible match needing a human decision'
            : 'possible matches needing a human decision';
    }

    get reviewCount() {
        return this.view?.reviews?.length ?? 0;
    }

    get footnote() {
        if (!this.view) {
            return '';
        }
        return `${this.view.note} Schema ${this.view.schemaTag}, ${this.view.cpuMillis} ms of Apex CPU.`;
    }
}
