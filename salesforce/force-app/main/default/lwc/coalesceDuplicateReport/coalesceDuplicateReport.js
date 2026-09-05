import { LightningElement, api } from 'lwc';
import findDuplicates from '@salesforce/apex/CoalesceContactResolver.findDuplicates';

/**
 * Org-wide duplicate report: every cluster among the contacts scanned, with the evidence for each.
 *
 * Deliberately triggered by a button rather than a @wire. All-pairs comparison is quadratic and
 * costs around 4 seconds of Apex CPU at 100 contacts, so running it automatically would add that
 * to every Home page load whether or not the user came here to look at duplicates. The per-contact
 * panel can afford to autoload because it is linear and costs ~130ms; this cannot.
 */
export default class CoalesceDuplicateReport extends LightningElement {
    /** App Builder default. Kept read-only; the in-page control writes to limitOverride instead. */
    @api scanLimit = 100;

    limitOverride;
    view;
    error;
    running = false;

    get effectiveLimit() {
        return this.limitOverride ?? this.scanLimit;
    }

    async handleScan() {
        this.running = true;
        this.error = undefined;
        try {
            const data = await findDuplicates({ scanLimit: this.effectiveLimit });
            this.view = this.project(data);
        } catch (thrown) {
            this.error = this.readError(thrown);
            this.view = undefined;
        } finally {
            this.running = false;
        }
    }

    handleLimitChange(event) {
        const parsed = parseInt(event.detail.value, 10);
        if (!Number.isNaN(parsed) && parsed > 1) {
            this.limitOverride = parsed;
        }
    }

    project(data) {
        return {
            ...data,
            clusters: data.clusters.map((cluster, index) => ({
                ...cluster,
                heading: `Cluster ${index + 1} — ${cluster.size} records, one entity`,
                members: cluster.members.map((member) => ({
                    ...member,
                    link: `/lightning/r/Contact/${member.recordId}/view`,
                    detail:
                        [member.email, member.phone].filter(Boolean).join(' · ') ||
                        'no email or phone on file'
                })),
                evidence: cluster.evidence.map((pair) => ({
                    ...pair,
                    key: `${pair.leftId}-${pair.rightId}`,
                    headline: `${pair.leftName} ↔ ${pair.rightName}`
                }))
            })),
            reviewQueue: data.reviewQueue.map((pair) => ({
                ...pair,
                key: `${pair.leftId}-${pair.rightId}`,
                leftLink: `/lightning/r/Contact/${pair.leftId}/view`,
                rightLink: `/lightning/r/Contact/${pair.rightId}/view`
            }))
        };
    }

    readError(thrown) {
        if (Array.isArray(thrown.body)) {
            return thrown.body.map((entry) => entry.message).join(', ');
        }
        return thrown.body?.message ?? thrown.message ?? 'Unknown error';
    }

    get hasRun() {
        return Boolean(this.view);
    }

    get hasClusters() {
        return this.view?.clusters?.length > 0;
    }

    get hasReviewQueue() {
        return this.view?.reviewQueue?.length > 0;
    }

    get scanLabel() {
        return this.running ? 'Scanning…' : 'Scan for duplicates';
    }

    get noticeClass() {
        return this.view?.stoppedEarly
            ? 'slds-box slds-box_x-small slds-var-m-bottom_medium slds-text-body_small slds-theme_warning'
            : 'slds-box slds-box_x-small slds-var-m-bottom_medium slds-text-body_small';
    }

    get footnote() {
        if (!this.view) {
            return '';
        }
        return `Schema ${this.view.schemaTag}. ${this.view.note} Used ${this.view.cpuMillis} ms of the 10,000 ms Apex CPU budget.`;
    }
}
